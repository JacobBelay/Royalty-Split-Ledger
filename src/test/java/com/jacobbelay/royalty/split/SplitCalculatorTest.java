package com.jacobbelay.royalty.split;

import com.jacobbelay.royalty.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the remainder rule. Two kinds of test, on purpose:
 * <ul>
 *   <li><b>Example tests</b> with hand-checked numbers: easy to read, document the behaviour;</li>
 *   <li><b>A property test</b> ({@link #propertiesHoldForThousandsOfRandomSplits}): instead of checking specific
 *       outputs, it checks the two promises SplitCalculator makes on 5,000 random inputs. Examples catch the
 *       cases you thought of; properties catch the ones you didn't.</li>
 * </ul>
 */
class SplitCalculatorTest {

    private static List<Long> cents(List<Allocation> allocations) {
        return allocations.stream().map(a -> a.amount().cents()).toList();
    }

    @Test
    void evenSplitNeedsNoRemainder() {
        List<Allocation> result = SplitCalculator.split(Money.of("100.00"),
                List.of(Share.of(1, "50"), Share.of(2, "50")));
        assertThat(cents(result)).containsExactly(5000L, 5000L);
    }

    @Test
    void thirdsKeepTheLeftoverCentInsteadOfLosingIt() {
        // Exact shares: 3333.33..., 3333.33..., 3333.34 cents. Floors sum to 9999, so 1 cent is left over.
        // Collaborator 3 has the largest remainder (.34) and gets it.
        List<Allocation> result = SplitCalculator.split(Money.of("100.00"),
                List.of(Share.of(1, "33.3333"), Share.of(2, "33.3333"), Share.of(3, "33.3334")));
        assertThat(cents(result)).containsExactly(3333L, 3333L, 3334L);
        assertThat(cents(result).stream().mapToLong(Long::longValue).sum()).isEqualTo(10000L);
    }

    @Test
    void tiesAreBrokenByLowestCollaboratorIdRegardlessOfInputOrder() {
        // 1 cent, two equal 50% shares: both remainders are 0.5, so the tie-break decides. Lowest id wins.
        List<Allocation> forward = SplitCalculator.split(Money.of("0.01"), List.of(Share.of(4, "50"), Share.of(9, "50")));
        List<Allocation> reversed = SplitCalculator.split(Money.of("0.01"), List.of(Share.of(9, "50"), Share.of(4, "50")));
        assertThat(forward).containsExactly(new Allocation(4, Money.of("0.01")), new Allocation(9, Money.ZERO));
        assertThat(reversed).containsExactly(new Allocation(9, Money.ZERO), new Allocation(4, Money.of("0.01")));
    }

    @Test
    void outputOrderMatchesInputOrder() {
        List<Allocation> result = SplitCalculator.split(Money.of("10.00"),
                List.of(Share.of(30, "70"), Share.of(10, "30")));
        assertThat(result).extracting(Allocation::collaboratorId).containsExactly(30L, 10L);
        assertThat(cents(result)).containsExactly(700L, 300L);
    }

    @Test
    void zeroGrossGivesEveryoneZero() {
        assertThat(cents(SplitCalculator.split(Money.ZERO, List.of(Share.of(1, "60"), Share.of(2, "40")))))
                .containsExactly(0L, 0L);
    }

    @Test
    void singleOwnerGetsEverything() {
        assertThat(cents(SplitCalculator.split(Money.of("123.45"), List.of(Share.of(1, "100")))))
                .containsExactly(12345L);
    }

    @Test
    void handlesVeryLargeAmountsExactly() {
        Money gross = Money.of("9999999999999.99");
        List<Allocation> result = SplitCalculator.split(gross,
                List.of(Share.of(1, "33.3333"), Share.of(2, "33.3333"), Share.of(3, "33.3334")));
        assertThat(result.stream().map(Allocation::amount).reduce(Money.ZERO, Money::plus)).isEqualTo(gross);
    }

    @Test
    void rejectsNegativeGross() {
        assertThatThrownBy(() -> SplitCalculator.split(Money.of("-1.00"), List.of(Share.of(1, "100"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative");
    }

    @Test
    void rejectsSharesThatDoNotAddToExactlyOneHundred() {
        assertThatThrownBy(() -> SplitCalculator.validateShares(List.of(Share.of(1, "50"), Share.of(2, "49.9999"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("99.9999");
        assertThatThrownBy(() -> SplitCalculator.validateShares(List.of(Share.of(1, "60"), Share.of(2, "40.0001"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsOneHundredWrittenWithTrailingZeros() {
        SplitCalculator.validateShares(List.of(Share.of(1, "50.0000"), Share.of(2, "50.00")));   // no exception
    }

    @Test
    void rejectsDuplicateCollaborators() {
        assertThatThrownBy(() -> SplitCalculator.validateShares(List.of(Share.of(1, "50"), Share.of(1, "50"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("appears twice");
    }

    @Test
    void rejectsEmptyShares() {
        assertThatThrownBy(() -> SplitCalculator.validateShares(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shareRejectsOutOfRangeOrTooPrecisePercentages() {
        assertThatThrownBy(() -> Share.of(1, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Share.of(1, "100.0001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Share.of(1, "33.33333")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The two promises, checked on 5,000 random splits (fixed seed, so a failure is reproducible):
     * 1. allocations sum to exactly the gross (no cent created or lost);
     * 2. each person is less than 1 cent away from their exact share (the rounding is as fair as possible).
     */
    @Test
    void propertiesHoldForThousandsOfRandomSplits() {
        Random random = new Random(20260922L);
        for (int run = 0; run < 5_000; run++) {
            List<Share> shares = randomSharesSummingTo100(random);
            Money gross = Money.ofCents(random.nextLong(0, 10_000_000_000L));

            List<Allocation> result = SplitCalculator.split(gross, shares);

            long total = result.stream().mapToLong(a -> a.amount().cents()).sum();
            assertThat(total).as("run %d: sum must equal gross", run).isEqualTo(gross.cents());
            for (int i = 0; i < shares.size(); i++) {
                BigDecimal exact = BigDecimal.valueOf(gross.cents()).multiply(shares.get(i).percent()).divide(BigDecimal.valueOf(100));
                BigDecimal error = exact.subtract(BigDecimal.valueOf(result.get(i).amount().cents())).abs();
                assertThat(error).as("run %d, share %d: within 1 cent of exact", run, i).isLessThan(BigDecimal.ONE);
            }
        }
    }

    /** 1 to 8 random 4-decimal percentages; the last one absorbs whatever makes the total exactly 100. */
    private static List<Share> randomSharesSummingTo100(Random random) {
        int n = 1 + random.nextInt(8);
        List<Share> shares = new ArrayList<>();
        BigDecimal remaining = BigDecimal.valueOf(100);
        for (int i = 0; i < n - 1; i++) {
            // leave at least 0.0001 per remaining person
            BigDecimal max = remaining.subtract(new BigDecimal("0.0001").multiply(BigDecimal.valueOf(n - i)));
            BigDecimal pct = max.multiply(BigDecimal.valueOf(random.nextDouble())).setScale(4, RoundingMode.DOWN)
                    .max(new BigDecimal("0.0001"));
            shares.add(new Share(i + 1, pct));
            remaining = remaining.subtract(pct);
        }
        shares.add(new Share(n, remaining));
        return shares;
    }
}
