package com.jacobbelay.royalty.payout;

import com.jacobbelay.royalty.common.BusinessRuleException;
import com.jacobbelay.royalty.common.ConflictException;
import com.jacobbelay.royalty.common.NotFoundException;
import com.jacobbelay.royalty.money.Money;
import com.jacobbelay.royalty.split.Share;
import com.jacobbelay.royalty.statement.StatementImportCommand;
import com.jacobbelay.royalty.statement.StatementImportService;
import com.jacobbelay.royalty.statement.StatementLine;
import com.jacobbelay.royalty.support.AbstractPostgresIT;
import com.jacobbelay.royalty.support.Concurrently;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three payout protections from {@link PayoutService}'s class comment, each with its own test,
 * plus the headline concurrency test.
 */
class PayoutIT extends AbstractPostgresIT {

    @Autowired PayoutService payouts;
    @Autowired StatementImportService imports;

    /** A collaborator who owns 100% of a song that has earned {@code earned}. */
    private long collaboratorWithPayable(String threshold, String earned) {
        long c = collaborator("Ana", threshold).id();
        long song = release("Song", Share.of(c, "100"));
        imports.importStatement(new StatementImportCommand("DistroKid", "2026-08", List.of(new StatementLine(song, Money.of(earned)))));
        return c;
    }

    @Test
    void paysTheFullPayableBalanceAndClearsIt() {
        long c = collaboratorWithPayable("50.00", "120.00");

        PayoutResult result = payouts.requestPayout(c, "key-1");

        assertThat(result.amount()).isEqualTo(Money.of("120.00"));
        assertThat(result.duplicate()).isFalse();
        assertThat(payable(c)).isEqualTo(Money.ZERO);
        assertThat(cash()).isEqualTo(Money.ZERO);   // 120 in from the statement, 120 out
    }

    @Test
    void refusesBelowThresholdAndLetsTheBalanceAccumulate() {
        long c = collaboratorWithPayable("50.00", "49.99");

        assertThatThrownBy(() -> payouts.requestPayout(c, "key-1"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("49.99").hasMessageContaining("50.00");
        assertThat(payable(c)).isEqualTo(Money.of("49.99"));   // untouched
    }

    @Test
    void thresholdIsInclusive() {
        long c = collaboratorWithPayable("50.00", "50.00");
        assertThat(payouts.requestPayout(c, "key-1").amount()).isEqualTo(Money.of("50.00"));
    }

    @Test
    void zeroThresholdStillRefusesAnEmptyBalance() {
        long c = collaborator("Ana", "0.00").id();
        assertThatThrownBy(() -> payouts.requestPayout(c, "key-1")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void retryWithTheSameKeyReturnsTheOriginalPayoutInsteadOfPayingAgain() {
        long c = collaboratorWithPayable("50.00", "120.00");
        PayoutResult first = payouts.requestPayout(c, "key-1");

        PayoutResult retry = payouts.requestPayout(c, "key-1");

        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.payoutId()).isEqualTo(first.payoutId());
        assertThat(retry.amount()).isEqualTo(Money.of("120.00"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payout").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void reusingAKeyForAnotherCollaboratorIsAConflict() {
        long c = collaboratorWithPayable("50.00", "120.00");
        long other = collaborator("Ben", "50.00").id();
        payouts.requestPayout(c, "key-1");

        assertThatThrownBy(() -> payouts.requestPayout(other, "key-1")).isInstanceOf(ConflictException.class);
    }

    @Test
    void unknownCollaboratorAndMissingKeyAreRejected() {
        assertThatThrownBy(() -> payouts.requestPayout(999, "key-1")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> payouts.requestPayout(1, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The double-payout race. Eight requests with DIFFERENT keys (so idempotency can't save us; only the lock
     * can) hit the same $120 balance at the same instant. Exactly one must pay $120; the other seven must
     * wait for the lock, then see $0 and be refused. Remove {@code FOR UPDATE} from
     * CollaboratorRepository#lockForUpdate and this test fails with several payouts.
     */
    @Test
    void simultaneousPayoutRequestsPayExactlyOnce() throws Exception {
        long c = collaboratorWithPayable("50.00", "120.00");

        List<Concurrently.Outcome<PayoutResult>> outcomes =
                Concurrently.run(8, i -> payouts.requestPayout(c, "key-" + i));

        assertThat(outcomes.stream().filter(Concurrently.Outcome::succeeded).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
                .allMatch(o -> o.error() instanceof BusinessRuleException);
        assertThat(jdbc.sql("SELECT COALESCE(SUM(amount), 0) FROM payout").query(BigDecimal.class).single())
                .isEqualByComparingTo("120.00");
        assertThat(payable(c)).isEqualTo(Money.ZERO);
    }
}
