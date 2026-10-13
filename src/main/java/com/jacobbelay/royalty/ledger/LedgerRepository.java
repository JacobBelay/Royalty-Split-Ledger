package com.jacobbelay.royalty.ledger;

import com.jacobbelay.royalty.money.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the ledger tables ({@code journal_entry}, {@code journal_line}).
 *
 * <p><b>Append-only.</b> There is no update or delete method, by design; the schema would reject them anyway
 * (append-only trigger in V1__ledger_schema.sql). The only write is {@link #post}, which appends a whole
 * balanced {@link JournalEntry}. Mistakes get corrected by posting a new, opposite entry.
 *
 * <p><b>Balances are computed, never stored.</b> {@link #balance} sums lines on demand. A stored balance
 * column could disagree with the lines after a bug or a crash; a sum can't.
 *
 * <p>Must be called inside a transaction (the services are {@code @Transactional}), because an entry's
 * lines are only checked for balance at COMMIT by the deferred trigger. Outside a transaction each INSERT
 * would commit alone and the first line would fail as "unbalanced".
 *
 * <p>Uses {@link JdbcClient} (Spring 6.1+): plain SQL with named parameters. Chosen over JPA so every
 * statement that touches money is visible and exact.
 */
@Repository
public class LedgerRepository {

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Appends the entry and its lines. Returns the new journal_entry id. */
    public long post(JournalEntry entry, EntrySource source) {
        long entryId = jdbc.sql("""
                        INSERT INTO journal_entry (kind, description, statement_import_id, advance_id, payout_id)
                        VALUES (:kind, :description, :statementImportId, :advanceId, :payoutId)
                        RETURNING id
                        """)
                .param("kind", entry.kind().name())
                .param("description", entry.description())
                // Explicit SQL type: a bare null gives the driver no type to bind, so we tell it "BIGINT".
                .param("statementImportId", source.statementImportId(), Types.BIGINT)
                .param("advanceId", source.advanceId(), Types.BIGINT)
                .param("payoutId", source.payoutId(), Types.BIGINT)
                .query(Long.class)
                .single();

        for (EntryLine line : entry.lines()) {
            jdbc.sql("""
                            INSERT INTO journal_line (entry_id, account_code, collaborator_id, side, amount)
                            VALUES (:entryId, :code, :collaboratorId, :side, :amount)
                            """)
                    .param("entryId", entryId)
                    .param("code", line.account().code().name())
                    .param("collaboratorId", line.account().collaboratorId(), Types.BIGINT)
                    .param("side", line.side().code())
                    .param("amount", line.amount().amount())
                    .update();
        }
        return entryId;
    }

    /**
     * Current balance of one account, expressed on its normal side (see {@link AccountCode#normalSide()}):
     * ROYALTY_PAYABLE 40.00 means "we owe them $40"; ADVANCE_RECEIVABLE 60.00 means "they still owe $60".
     *
     * <p>Concurrency: under READ COMMITTED this sees only committed lines. Services that make decisions
     * from a balance (payouts, recoupment) lock the collaborator row first
     * ({@link com.jacobbelay.royalty.collaborator.CollaboratorRepository#lockForUpdate}) so no one else can
     * post for that collaborator between this read and their own write.
     */
    public Money balance(Account account) {
        String collaboratorFilter = account.collaboratorId() == null
                ? "collaborator_id IS NULL"
                : "collaborator_id = :collaboratorId";
        var spec = jdbc.sql("""
                        SELECT COALESCE(SUM(CASE WHEN side = :normalSide THEN amount ELSE -amount END), 0)
                          FROM journal_line
                         WHERE account_code = :code AND %s
                        """.formatted(collaboratorFilter))
                .param("normalSide", account.code().normalSide().code())
                .param("code", account.code().name());
        if (account.collaboratorId() != null) {
            spec = spec.param("collaboratorId", account.collaboratorId());
        }
        return new Money(spec.query(BigDecimal.class).single());
    }

    /**
     * Rebuilds per-collaborator earnings for a statement from its ledger lines. Used to answer a duplicate
     * import with the same numbers as the original, without storing them twice
     * (see StatementImportService#importStatement).
     */
    public List<Postings.Earning> earningsForStatement(long statementImportId) {
        record Row(long collaboratorId, String code, BigDecimal amount) {
        }
        List<Row> rows = jdbc.sql("""
                        SELECT l.collaborator_id, l.account_code, l.amount
                          FROM journal_line l
                          JOIN journal_entry e ON e.id = l.entry_id
                         WHERE e.statement_import_id = :id AND l.collaborator_id IS NOT NULL
                         ORDER BY l.collaborator_id
                        """)
                .param("id", statementImportId)
                .query((rs, rowNum) -> new Row(rs.getLong("collaborator_id"), rs.getString("account_code"), rs.getBigDecimal("amount")))
                .list();

        Map<Long, Money[]> byCollaborator = new LinkedHashMap<>();   // [recouped, payable], in collaborator order
        for (Row row : rows) {
            Money[] parts = byCollaborator.computeIfAbsent(row.collaboratorId(), id -> new Money[]{Money.ZERO, Money.ZERO});
            int slot = AccountCode.valueOf(row.code()) == AccountCode.ADVANCE_RECEIVABLE ? 0 : 1;
            parts[slot] = parts[slot].plus(new Money(row.amount()));
        }
        List<Postings.Earning> result = new ArrayList<>();
        byCollaborator.forEach((id, parts) -> result.add(new Postings.Earning(id, parts[0], parts[1])));
        return result;
    }

    /**
     * Total debits and credits across the entire ledger. They must always be equal; the API exposes this
     * (GET /api/ledger/trial-balance) and the tests assert it after every scenario.
     */
    public TrialBalance trialBalance() {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount) FILTER (WHERE side = 'D'), 0) AS debits,
                               COALESCE(SUM(amount) FILTER (WHERE side = 'C'), 0) AS credits
                          FROM journal_line
                        """)
                .query((rs, rowNum) -> new TrialBalance(new Money(rs.getBigDecimal("debits")), new Money(rs.getBigDecimal("credits"))))
                .single();
    }

    public record TrialBalance(Money debits, Money credits) {
        public boolean balanced() {
            return debits.compareTo(credits) == 0;
        }
    }
}
