-- =====================================================================================================
-- V1: LEDGER SCHEMA
-- What: every table the service uses, plus the database-level rules that make the ledger trustworthy.
-- Why the rules live HERE and not only in Java: application code has bugs and can be bypassed (a psql
--   session, a future script). Constraints and triggers hold no matter who writes. Java validates first
--   for friendly errors (see ledger/JournalEntry.java); the database is the last line of defence.
-- Read next: ledger/LedgerRepository.java (writes these tables) and docs/LEARNING_GUIDE.md §2.
-- Flyway runs this file once, in order, and records it in flyway_schema_history. Never edit an applied
--   migration; add V2__... instead.
-- =====================================================================================================

-- ---------- Reference data ---------------------------------------------------------------------------

-- A person who earns royalties. payout_threshold = the minimum payable balance before we send money
-- (see payout/PayoutService.java): small transfers cost more in fees than they're worth.
CREATE TABLE collaborator (
    id               BIGSERIAL PRIMARY KEY,
    name             TEXT          NOT NULL,
    payout_threshold NUMERIC(19,2) NOT NULL DEFAULT 50.00 CHECK (payout_threshold >= 0),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- A song/album whose revenue gets split.
CREATE TABLE release (
    id         BIGSERIAL PRIMARY KEY,
    title      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Who owns what percentage of a release. NUMERIC(7,4) allows 33.3333%.
-- "Shares add to exactly 100" is checked in Java (split/SplitCalculator.java) when the release is created,
-- because a per-row CHECK can't see sibling rows.
CREATE TABLE release_share (
    release_id      BIGINT       NOT NULL REFERENCES release(id),
    collaborator_id BIGINT       NOT NULL REFERENCES collaborator(id),
    percent         NUMERIC(7,4) NOT NULL CHECK (percent > 0 AND percent <= 100),
    PRIMARY KEY (release_id, collaborator_id)   -- one share per person per release
);

-- ---------- Idempotent statement imports -------------------------------------------------------------

-- One row per distributor statement we've processed.
-- UNIQUE (source, external_ref) is the idempotency key: importing "DistroKid / 2026-08" twice must not
-- pay anyone twice. content_sha256 lets us tell "same file sent again" (safe, return the first result)
-- apart from "different numbers under the same name" (a real conflict, HTTP 409).
-- See statement/StatementImportService.java for how INSERT ... ON CONFLICT uses this.
CREATE TABLE statement_import (
    id             BIGSERIAL PRIMARY KEY,
    source         TEXT          NOT NULL,
    external_ref   TEXT          NOT NULL,
    content_sha256 CHAR(64)      NOT NULL,
    gross          NUMERIC(19,2) NOT NULL CHECK (gross >= 0),
    imported_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (source, external_ref)
);

-- ---------- Money events that aren't statements ------------------------------------------------------

-- Money paid to a collaborator BEFORE they earn it. Future royalties pay it back ("recoupment",
-- see recoupment/RecoupmentCalculator.java). The balance owed lives in the ledger, not here;
-- this table is just the business record of the event.
CREATE TABLE advance (
    id              BIGSERIAL PRIMARY KEY,
    collaborator_id BIGINT        NOT NULL REFERENCES collaborator(id),
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Money actually sent out. idempotency_key comes from the client's Idempotency-Key header, so a retried
-- HTTP request (network blip, double click) returns the first payout instead of paying twice.
CREATE TABLE payout (
    id              BIGSERIAL PRIMARY KEY,
    collaborator_id BIGINT        NOT NULL REFERENCES collaborator(id),
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    idempotency_key TEXT          NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ---------- The double-entry ledger ------------------------------------------------------------------
-- Double entry in one sentence: every event is recorded as lines whose debits equal their credits,
-- so money is never created or destroyed, only moved between accounts.
-- Balances are never stored; they're always SUM()'d from lines (ledger/LedgerRepository#balance).
-- That makes the lines the single source of truth: no "balance" column can drift out of sync.

CREATE TABLE journal_entry (
    id                  BIGSERIAL PRIMARY KEY,
    kind                TEXT        NOT NULL CHECK (kind IN ('STATEMENT', 'ADVANCE', 'PAYOUT')),
    description         TEXT        NOT NULL,
    statement_import_id BIGINT      REFERENCES statement_import(id),
    advance_id          BIGINT      REFERENCES advance(id),
    payout_id           BIGINT      REFERENCES payout(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- account_code + collaborator_id together name an account:
--   ('CASH', NULL)                 our bank account
--   ('ROYALTY_PAYABLE', 7)         what we owe collaborator 7           (liability, credit-normal)
--   ('ADVANCE_RECEIVABLE', 7)      advance collaborator 7 still owes us (asset, debit-normal)
-- The CHECK keeps that pairing honest. Mirrors ledger/AccountCode.java.
CREATE TABLE journal_line (
    id              BIGSERIAL PRIMARY KEY,
    entry_id        BIGINT        NOT NULL REFERENCES journal_entry(id),
    account_code    TEXT          NOT NULL CHECK (account_code IN ('CASH', 'ROYALTY_PAYABLE', 'ADVANCE_RECEIVABLE')),
    collaborator_id BIGINT        REFERENCES collaborator(id),
    side            CHAR(1)       NOT NULL CHECK (side IN ('D', 'C')),
    amount          NUMERIC(19,2) NOT NULL CHECK (amount > 0),
    CHECK ((account_code = 'CASH') = (collaborator_id IS NULL))
);

-- Balance queries filter by (account_code, collaborator_id); this index keeps them fast as lines grow.
CREATE INDEX journal_line_account_idx ON journal_line (account_code, collaborator_id);
CREATE INDEX journal_line_entry_idx   ON journal_line (entry_id);

-- ---------- Rule 1: append-only ----------------------------------------------------------------------
-- Accounting records are never edited. A mistake is fixed with a NEW reversing entry, so the history
-- of what happened (and when we knew it) survives audits. This trigger rejects UPDATE and DELETE outright.
-- (TRUNCATE is a separate event it doesn't cover; the test suite relies on that to reset data,
--  and production credentials should simply not have TRUNCATE privilege.)
CREATE FUNCTION reject_ledger_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TRIGGER journal_line_append_only
    BEFORE UPDATE OR DELETE ON journal_line
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- ---------- Rule 2: every entry balances -------------------------------------------------------------
-- An entry's lines are inserted one at a time, so mid-insert the entry is temporarily unbalanced.
-- DEFERRABLE INITIALLY DEFERRED postpones this check to COMMIT, when all lines are present.
-- If debits != credits (or an entry has fewer than 2 lines), the whole transaction rolls back.
CREATE FUNCTION assert_entry_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    debits  NUMERIC;
    credits NUMERIC;
    n_lines INT;
BEGIN
    SELECT COALESCE(SUM(amount) FILTER (WHERE side = 'D'), 0),
           COALESCE(SUM(amount) FILTER (WHERE side = 'C'), 0),
           COUNT(*)
      INTO debits, credits, n_lines
      FROM journal_line
     WHERE entry_id = NEW.entry_id;

    IF n_lines < 2 OR debits <> credits THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debits=% credits=% lines=%',
            NEW.entry_id, debits, credits, n_lines
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;   -- AFTER triggers' return value is ignored
END;
$$;

CREATE CONSTRAINT TRIGGER journal_line_balanced
    AFTER INSERT ON journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_entry_balanced();
