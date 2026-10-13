package com.jacobbelay.royalty.ledger;

import com.jacobbelay.royalty.money.Money;
import com.jacobbelay.royalty.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the database enforces the ledger rules <em>on its own</em>, by writing raw SQL that skips all the
 * Java checks. If someone edits the ledger from psql or a future script, these are the guarantees that hold.
 * The triggers under test are in V1__ledger_schema.sql.
 */
class LedgerDatabaseRulesIT extends AbstractPostgresIT {

    @Autowired
    TransactionTemplate tx;   // runs a lambda in one transaction and commits it (or rolls back on exception)

    @Test
    void postedEntriesCannotBeUpdated() {
        long c = collaborator("Ana", "50.00").id();
        // Posting needs a transaction: the deferred balance trigger checks at COMMIT, after all lines exist.
        tx.executeWithoutResult(s -> ledger.post(Postings.advanceIssued(c, Money.of("100.00")), EntrySource.advance(insertAdvance(c))));

        assertThatThrownBy(() -> jdbc.sql("UPDATE journal_line SET amount = 1").update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");
    }

    @Test
    void postedEntriesCannotBeDeleted() {
        long c = collaborator("Ana", "50.00").id();
        tx.executeWithoutResult(s -> ledger.post(Postings.advanceIssued(c, Money.of("100.00")), EntrySource.advance(insertAdvance(c))));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entry").update())
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_line").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void unbalancedEntryIsRejectedAtCommitEvenWhenJavaIsBypassed() {
        long c = collaborator("Ana", "50.00").id();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            long entryId = jdbc.sql("INSERT INTO journal_entry (kind, description) VALUES ('ADVANCE', 'hand-written') RETURNING id")
                    .query(Long.class).single();
            jdbc.sql("INSERT INTO journal_line (entry_id, account_code, collaborator_id, side, amount) VALUES (:e, 'ADVANCE_RECEIVABLE', :c, 'D', 100)")
                    .param("e", entryId).param("c", c).update();
            jdbc.sql("INSERT INTO journal_line (entry_id, account_code, collaborator_id, side, amount) VALUES (:e, 'CASH', NULL, 'C', 99)")
                    .param("e", entryId).update();
            // Each INSERT succeeds. The deferred trigger only checks at COMMIT, which is where this blows up.
        }))
                // A failed COMMIT surfaces as a Spring exception wrapping Postgres's error; the trigger's text is in the cause chain.
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("unbalanced");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM journal_line").query(Long.class).single()).isZero();   // fully rolled back
    }

    @Test
    void balancesAreDerivedOnEachAccountsNormalSide() {
        long c = collaborator("Ana", "50.00").id();
        tx.executeWithoutResult(s -> ledger.post(Postings.advanceIssued(c, Money.of("100.00")), EntrySource.advance(insertAdvance(c))));

        assertThat(advanceOutstanding(c)).isEqualTo(Money.of("100.00"));   // asset: debit-normal, shows +100
        assertThat(cash()).isEqualTo(Money.of("-100.00"));                 // we paid out 100 we hadn't received yet
        assertThat(payable(c)).isEqualTo(Money.ZERO);
    }

    private long insertAdvance(long collaboratorId) {
        return jdbc.sql("INSERT INTO advance (collaborator_id, amount) VALUES (:c, 100) RETURNING id")
                .param("c", collaboratorId).query(Long.class).single();
    }
}
