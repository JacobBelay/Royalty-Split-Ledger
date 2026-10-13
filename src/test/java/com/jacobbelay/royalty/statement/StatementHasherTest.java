package com.jacobbelay.royalty.statement;

import com.jacobbelay.royalty.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idempotency check trusts this fingerprint, so it must be stable for equivalent content and different
 * for any real change. (StatementImportIT covers what the service does with a match or a mismatch.)
 */
class StatementHasherTest {

    @Test
    void ignoresLineOrderAndNumberFormatting() {
        String a = StatementHasher.sha256(List.of(new StatementLine(1, Money.of("5")), new StatementLine(2, Money.of("7.10"))));
        String b = StatementHasher.sha256(List.of(new StatementLine(2, new Money(new BigDecimal("7.1"))), new StatementLine(1, Money.of("5.00"))));
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void changesWhenAnyAmountChangesByOneCent() {
        String a = StatementHasher.sha256(List.of(new StatementLine(1, Money.of("5.00"))));
        String b = StatementHasher.sha256(List.of(new StatementLine(1, Money.of("5.01"))));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void changesWhenMoneyMovesBetweenReleases() {
        String a = StatementHasher.sha256(List.of(new StatementLine(1, Money.of("5.00")), new StatementLine(2, Money.of("1.00"))));
        String b = StatementHasher.sha256(List.of(new StatementLine(1, Money.of("1.00")), new StatementLine(2, Money.of("5.00"))));
        assertThat(a).isNotEqualTo(b);
    }
}
