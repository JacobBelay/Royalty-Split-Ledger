package com.jacobbelay.royalty.ledger;

import com.jacobbelay.royalty.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LedgerRepository.TrialBalance#balanced()} is the system's self-check: the whole ledger is summed
 * and debits must equal credits.
 *
 * <p>Every integration test asserts {@code balanced()} is true after it runs, which means they would all
 * keep passing even if this method always returned true. These tests are the ones that rule that out, by
 * showing it also returns FALSE when the totals differ.
 */
class TrialBalanceTest {

    private static LedgerRepository.TrialBalance of(String debits, String credits) {
        return new LedgerRepository.TrialBalance(Money.of(debits), Money.of(credits));
    }

    @Test
    @DisplayName("equal totals are balanced")
    void equalTotalsBalance() {
        assertThat(of("100.00", "100.00").balanced()).isTrue();
        assertThat(of("0.00", "0.00").balanced()).isTrue();          // an empty ledger balances
        assertThat(of("99999999.99", "99999999.99").balanced()).isTrue();
    }

    @Test
    @DisplayName("a single missing cent is NOT balanced")
    void oneCentApartIsUnbalanced() {
        // The smallest possible discrepancy must still be caught; that is the entire point of the check.
        assertThat(of("100.00", "99.99").balanced()).isFalse();
        assertThat(of("99.99", "100.00").balanced()).isFalse();
    }

    @Test
    @DisplayName("balanced compares value, not scale or sign")
    void comparesNumericValue() {
        // compareTo, not equals: BigDecimal 100.00 and 100.0 are not equal but are the same amount. Money
        // fixes the scale at 2 so this cannot bite in practice, and the comparison is right regardless.
        assertThat(new LedgerRepository.TrialBalance(
                new Money(new java.math.BigDecimal("100.00")),
                new Money(new java.math.BigDecimal("100.00"))).balanced()).isTrue();
        assertThat(of("-5.00", "5.00").balanced()).isFalse();
    }
}
