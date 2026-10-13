package com.jacobbelay.royalty.ledger;

import com.jacobbelay.royalty.money.Money;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks each business event moves exactly the accounts drawn in the table in {@link Postings}. */
class PostingsTest {

    @Test
    void advanceMovesMoneyFromCashIntoAdvanceReceivable() {
        JournalEntry e = Postings.advanceIssued(7, Money.of("1000.00"));
        assertThat(e.kind()).isEqualTo(EntryKind.ADVANCE);
        assertThat(e.lines()).containsExactly(
                EntryLine.debit(Account.advanceReceivable(7), Money.of("1000.00")),
                EntryLine.credit(Account.cash(), Money.of("1000.00")));
    }

    @Test
    void statementCreditsRecoupedAndPayablePartsSeparatelyAndSkipsZeros() {
        JournalEntry e = Postings.statementReceived("DK / 2026-08", Money.of("100.00"), List.of(
                new Postings.Earning(1, Money.of("40.00"), Money.of("20.00")),   // partly recouped
                new Postings.Earning(2, Money.ZERO, Money.of("40.00"))));        // no advance: no ADVANCE line
        assertThat(e.lines()).containsExactly(
                EntryLine.debit(Account.cash(), Money.of("100.00")),
                EntryLine.credit(Account.advanceReceivable(1), Money.of("40.00")),
                EntryLine.credit(Account.payable(1), Money.of("20.00")),
                EntryLine.credit(Account.payable(2), Money.of("40.00")));
    }

    @Test
    void statementWhoseEarningsDontMatchGrossIsRejected() {
        // A bug upstream (e.g. a lost cent) is caught here by JournalEntry's balance check, not written to the ledger.
        assertThatThrownBy(() -> Postings.statementReceived("bad", Money.of("100.00"),
                List.of(new Postings.Earning(1, Money.ZERO, Money.of("99.99")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unbalanced");
    }

    @Test
    void payoutClearsPayableAgainstCash() {
        JournalEntry e = Postings.payoutSent(3, Money.of("75.50"));
        assertThat(e.lines()).containsExactly(
                EntryLine.debit(Account.payable(3), Money.of("75.50")),
                EntryLine.credit(Account.cash(), Money.of("75.50")));
    }
}
