package com.jacobbelay.royalty.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Side} is the one-character 'D'/'C' stored in {@code journal_line.side}.
 *
 * <p>Worth its own test because it is the boundary between Java and SQL: the enum's {@code code()} must keep
 * matching the {@code CHECK (side IN ('D','C'))} constraint in V1__ledger_schema.sql forever. Renaming a
 * code would compile cleanly and then fail at runtime on every insert, so these assertions pin the two
 * letters in place.
 */
class SideTest {

    @Test
    @DisplayName("the codes are exactly the two letters the schema allows")
    void codesMatchTheSchema() {
        assertThat(Side.DEBIT.code()).isEqualTo("D");
        assertThat(Side.CREDIT.code()).isEqualTo("C");
        assertThat(Side.values()).hasSize(2);     // a third side would have no meaning in double entry
    }

    @Test
    @DisplayName("codes round-trip back to the enum")
    void fromCodeRoundTrips() {
        assertThat(Side.fromCode("D")).isEqualTo(Side.DEBIT);
        assertThat(Side.fromCode("C")).isEqualTo(Side.CREDIT);

        for (Side side : Side.values()) {
            assertThat(Side.fromCode(side.code())).isEqualTo(side);
        }
    }

    @Test
    @DisplayName("an unknown code throws rather than guessing a side")
    void unknownCodeThrows() {
        // Guessing here would silently turn a debit into a credit, i.e. move money the wrong way.
        assertThatThrownBy(() -> Side.fromCode("X")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Side.fromCode("d")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Side.fromCode("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("each account's normal side is the direction that increases it")
    void normalSidesAreTheAccountingConvention() {
        // Assets grow on the debit side; liabilities grow on the credit side. LedgerRepository#balance uses
        // this to decide the sign of each line, so getting one wrong would report negative balances.
        assertThat(AccountCode.CASH.normalSide()).isEqualTo(Side.DEBIT);                 // asset
        assertThat(AccountCode.ADVANCE_RECEIVABLE.normalSide()).isEqualTo(Side.DEBIT);   // asset (they owe us)
        assertThat(AccountCode.ROYALTY_PAYABLE.normalSide()).isEqualTo(Side.CREDIT);     // liability (we owe them)
    }
}
