package com.jacobbelay.royalty.split;

import com.jacobbelay.royalty.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Splits an amount among collaborators by percentage so that <b>every cent is accounted for</b>.
 *
 * <p><b>The problem.</b> $100.00 split three ways at 33.3333% each is 33.33333... per person. Round each
 * share normally and you pay out $99.99; one cent vanishes. Round up and you pay $100.02, which is money
 * you don't have. Across thousands of statements those cents become real discrepancies.
 *
 * <p><b>The remainder rule</b> (a.k.a. the largest-remainder or Hamilton method):
 * <ol>
 *   <li>Work in whole cents. Compute each person's exact share, e.g. 3333.3333 cents.</li>
 *   <li>Give everyone the floor: 3333 cents each. Total so far: 9999.</li>
 *   <li>The leftover (10000 - 9999 = 1 cent) is always less than one cent per person. Hand those cents
 *       out one at a time to whoever lost the most in step 2 (largest fractional remainder).</li>
 *   <li>Ties are broken by lowest collaborator id, so the same input always gives the same output.
 *       Determinism matters: an audit that re-runs the split must get identical numbers.</li>
 * </ol>
 * Result: allocations always sum to exactly {@code gross}, and nobody is more than 1 cent from their
 * exact share. The tests in {@code SplitCalculatorTest} check both properties on thousands of random inputs.
 *
 * <p>This class is pure (no database, no Spring), which is why it's cheap to test exhaustively.
 * It's called by {@link com.jacobbelay.royalty.statement.StatementImportService} for every statement line,
 * and by {@link com.jacobbelay.royalty.release.ReleaseService} to validate shares when a release is created.
 */
public final class SplitCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SplitCalculator() {
    }

    /**
     * @param gross  the amount to split; must be zero or positive
     * @param shares must be valid per {@link #validateShares}
     * @return one allocation per share, in the same order as {@code shares}, summing exactly to {@code gross}
     */
    public static List<Allocation> split(Money gross, List<Share> shares) {
        Objects.requireNonNull(gross, "gross");
        if (gross.isNegative()) {
            throw new IllegalArgumentException("cannot split a negative amount: " + gross);
        }
        validateShares(shares);

        long grossCents = gross.cents();

        // Steps 1 and 2: exact share in cents, then the floor of it.
        // exact = grossCents * percent / 100. Dividing by 100 always terminates in base 10,
        // so this BigDecimal division is exact and needs no rounding mode.
        int n = shares.size();
        long[] cents = new long[n];
        BigDecimal[] remainder = new BigDecimal[n];
        long allocated = 0;
        for (int i = 0; i < n; i++) {
            BigDecimal exact = BigDecimal.valueOf(grossCents).multiply(shares.get(i).percent()).divide(HUNDRED);
            BigDecimal floor = exact.setScale(0, RoundingMode.FLOOR);
            cents[i] = floor.longValueExact();
            remainder[i] = exact.subtract(floor);           // the fraction of a cent this person lost, 0 <= r < 1
            allocated += cents[i];
        }

        // Step 3: leftover cents. Because shares sum to 100%, the lost fractions sum to a whole number < n.
        long leftover = grossCents - allocated;

        // Step 4: rank by largest remainder, ties by lowest collaborator id, and give the top `leftover` people +1 cent.
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator
                .<Integer, BigDecimal>comparing(i -> remainder[i]).reversed()
                .thenComparingLong(i -> shares.get(i).collaboratorId()));
        for (int k = 0; k < leftover; k++) {
            cents[order.get(k)] += 1;
        }

        List<Allocation> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(new Allocation(shares.get(i).collaboratorId(), Money.ofCents(cents[i])));
        }
        return result;
    }

    /**
     * Group rules a single {@link Share} can't check alone: at least one share, no collaborator listed twice,
     * and percentages adding to <b>exactly</b> 100 ({@code compareTo}, so 100 and 100.0000 are equal).
     *
     * @throws IllegalArgumentException with a message safe to show the API caller
     */
    public static void validateShares(List<Share> shares) {
        if (shares == null || shares.isEmpty()) {
            throw new IllegalArgumentException("a release needs at least one share");
        }
        Set<Long> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Share share : shares) {
            if (!seen.add(share.collaboratorId())) {
                throw new IllegalArgumentException("collaborator " + share.collaboratorId() + " appears twice");
            }
            total = total.add(share.percent());
        }
        if (total.compareTo(HUNDRED) != 0) {
            throw new IllegalArgumentException("shares must add up to exactly 100%, got " + total.stripTrailingZeros().toPlainString() + "%");
        }
    }
}
