package com.jacobbelay.royalty.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins down the rules every other class relies on: fixed scale, exact arithmetic, and no silent rounding
 * (see the Money class comment for why each matters).
 */
class MoneyTest {

    @Test
    void normalisesScaleSoEqualAmountsAreEqual() {
        // Raw BigDecimal says 1.0 != 1.00; Money must not, or map keys and comparisons break.
        assertThat(new Money(new BigDecimal("1.0"))).isEqualTo(Money.of("1.00"));
        assertThat(Money.of("7").toString()).isEqualTo("7.00");
    }

    @Test
    void refusesToRoundSubCentValues() {
        assertThatThrownBy(() -> Money.of("1.005")).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void addsWithoutFloatingPointDrift() {
        // With double this would be 0.30000000000000004.
        assertThat(Money.of("0.10").plus(Money.of("0.20"))).isEqualTo(Money.of("0.30"));
    }

    @Test
    void convertsToAndFromWholeCents() {
        assertThat(Money.ofCents(1234)).isEqualTo(Money.of("12.34"));
        assertThat(Money.of("12.34").cents()).isEqualTo(1234);
        assertThat(Money.of("-0.01").cents()).isEqualTo(-1);
    }

    @Test
    void comparesAndPicksMinMax() {
        Money small = Money.of("5.00");
        Money big = Money.of("9.99");
        assertThat(small.isLessThan(big)).isTrue();
        assertThat(Money.min(small, big)).isEqualTo(small);
        assertThat(Money.max(small, big)).isEqualTo(big);
        assertThat(Money.ZERO.isZero()).isTrue();
        assertThat(big.minus(small)).isEqualTo(Money.of("4.99"));
        assertThat(small.minus(big).isNegative()).isTrue();
    }

    @Test
    void printsPlainDecimalsNeverScientificNotation() {
        assertThat(new Money(new BigDecimal("1E+3")).toString()).isEqualTo("1000.00");
    }
}
