package com.jacobbelay.royalty.ledger;

/**
 * The business event behind a journal entry. Matches the CHECK on {@code journal_entry.kind}.
 * Each kind has exactly one builder in {@link Postings}.
 */
public enum EntryKind {
    /** Revenue from a distributor statement, split and possibly partly recouped. See Postings#statementReceived. */
    STATEMENT,
    /** Money fronted to a collaborator. See Postings#advanceIssued. */
    ADVANCE,
    /** Money sent to a collaborator, clearing what we owed. See Postings#payoutSent. */
    PAYOUT
}
