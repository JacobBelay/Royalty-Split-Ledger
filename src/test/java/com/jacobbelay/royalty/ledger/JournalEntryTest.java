package com.jacobbelay.royalty.ledger;

import com.jacobbelay.royalty.money.Money;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Java half of the "every entry balances" rule (the database half is tested in LedgerDatabaseRulesIT).
 * Also covers {@link Account} and {@link EntryLine} validation, which feed into it.
 */
class JournalEntryTest {

    private static final Money TEN = Money.of("10.00");

    @Test
    void acceptsABalancedEntry() {
        JournalEntry entry = new JournalEntry(EntryKind.PAYOUT, "ok",
                List.of(EntryLine.debit(Account.payable(1), TEN), EntryLine.credit(Account.cash(), TEN)));
        assertThat(entry.totalDebits()).isEqualTo(TEN);
    }

    @Test
    void rejectsUnbalancedEntryAndNamesTheGap() {
        assertThatThrownBy(() -> new JournalEntry(EntryKind.PAYOUT, "bad",
                List.of(EntryLine.debit(Account.payable(1), TEN), EntryLine.credit(Account.cash(), Money.of("9.99")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10.00").hasMessageContaining("9.99");
    }

    @Test
    void rejectsSingleLineEntry() {
        assertThatThrownBy(() -> new JournalEntry(EntryKind.PAYOUT, "one", List.of(EntryLine.debit(Account.cash(), TEN))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isImmutableAfterConstruction() {
        List<EntryLine> lines = new ArrayList<>(List.of(EntryLine.debit(Account.payable(1), TEN), EntryLine.credit(Account.cash(), TEN)));
        JournalEntry entry = new JournalEntry(EntryKind.PAYOUT, "copy", lines);
        lines.add(EntryLine.debit(Account.cash(), TEN));   // mutate the caller's list afterwards...
        assertThat(entry.lines()).hasSize(2);                // ...the validated entry is unaffected
        assertThatThrownBy(() -> entry.lines().add(EntryLine.debit(Account.cash(), TEN)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void linesMustBePositive() {
        assertThatThrownBy(() -> EntryLine.debit(Account.cash(), Money.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntryLine.credit(Account.cash(), Money.of("-1.00"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountsEnforceTheCollaboratorPairingRule() {
        assertThatThrownBy(() -> new Account(AccountCode.CASH, 5L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Account(AccountCode.ROYALTY_PAYABLE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Account.advanceReceivable(5).collaboratorId()).isEqualTo(5L);
    }

    @Test
    void normalSidesFollowAccountingConvention() {
        assertThat(AccountCode.CASH.normalSide()).isEqualTo(Side.DEBIT);                 // asset
        assertThat(AccountCode.ADVANCE_RECEIVABLE.normalSide()).isEqualTo(Side.DEBIT);   // asset
        assertThat(AccountCode.ROYALTY_PAYABLE.normalSide()).isEqualTo(Side.CREDIT);     // liability
        assertThat(Side.fromCode("C")).isEqualTo(Side.CREDIT);
    }
}
