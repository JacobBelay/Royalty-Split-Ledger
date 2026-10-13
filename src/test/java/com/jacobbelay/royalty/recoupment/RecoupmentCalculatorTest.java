package com.jacobbelay.royalty.recoupment;

import com.jacobbelay.royalty.money.Money;
import com.jacobbelay.royalty.recoupment.RecoupmentCalculator.Recoupment;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One table covers every recoupment case. {@code @ParameterizedTest} runs the method once per CSV row,
 * so adding a case is one line, and each row shows up as its own test in the report.
 */
class RecoupmentCalculatorTest {

    @ParameterizedTest(name = "earned {0}, owed {1} -> recouped {2}, payable {3}")
    @CsvSource({
            // earned,  outstanding, recouped, payable
            "500.00,    0.00,         0.00,     500.00",   // no advance: everything payable
            "500.00,    300.00,       300.00,   200.00",   // advance fully recouped this statement, rest payable
            "200.00,    300.00,       200.00,   0.00",     // advance bigger than earnings: all of it recoups
            "300.00,    300.00,       300.00,   0.00",     // exact payoff
            "0.00,      300.00,       0.00,     0.00",     // nothing earned, nothing changes
            "50.00,     -10.00,       0.00,     50.00",    // over-recouped (negative owed) is treated as nothing owed
    })
    void splitsEarningsBetweenRecoupmentAndPayable(String earned, String outstanding, String recouped, String payable) {
        Recoupment r = RecoupmentCalculator.apply(Money.of(earned), Money.of(outstanding));
        assertThat(r.recouped()).isEqualTo(Money.of(recouped));
        assertThat(r.payable()).isEqualTo(Money.of(payable));
        assertThat(r.recouped().plus(r.payable())).as("nothing created or lost").isEqualTo(Money.of(earned));
    }

    @Test
    void rejectsNegativeEarnings() {
        assertThatThrownBy(() -> RecoupmentCalculator.apply(Money.of("-1.00"), Money.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
