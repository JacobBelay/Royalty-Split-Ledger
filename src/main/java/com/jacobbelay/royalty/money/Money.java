package com.jacobbelay.royalty.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount of money in one currency (CAD), always stored with exactly 2 decimal places (cents).
 *
 * <p><b>Why not {@code double}?</b> Binary floating point can't represent 0.10 exactly:
 * {@code 0.1 + 0.2 == 0.30000000000000004}. Over thousands of royalty lines those errors add up to cents
 * that belong to nobody. {@link BigDecimal} stores base-10 digits exactly.
 *
 * <p><b>Why a wrapper instead of raw BigDecimal?</b> {@code new BigDecimal("1.0").equals(new BigDecimal("1.00"))}
 * is {@code false} (different scale). Forcing scale 2 in the constructor makes equality, hashing and JSON
 * output predictable, and the type name documents intent in every method signature.
 *
 * <p><b>Rule: we never round silently.</b> {@link RoundingMode#UNNECESSARY} throws if a value has more than
 * 2 decimals. The only place fractions of a cent legitimately appear is splitting, and
 * {@link com.jacobbelay.royalty.split.SplitCalculator} handles those explicitly with the remainder rule.
 *
 * <p>Negative values are allowed (a balance can go negative); rules like "line amounts must be positive"
 * are enforced where they apply (see {@link com.jacobbelay.royalty.ledger.EntryLine}).
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    /** Compact constructor: runs on every construction, so no Money with the wrong scale can exist. */
    public Money {
        Objects.requireNonNull(amount, "amount");
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);   // throws ArithmeticException on 1.005
    }

    /** {@code Money.of("12.34")}. Strings avoid the double-literal trap: {@code new BigDecimal(0.1)} is 0.1000000000000000055... */
    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    /** Build from whole cents, e.g. {@code ofCents(1234)} is 12.34. Used by the split math, which works in integer cents. */
    public static Money ofCents(long cents) {
        return new Money(BigDecimal.valueOf(cents, 2));
    }

    /** The amount as whole cents, e.g. 12.34 gives 1234. {@code longValueExact} throws rather than overflow silently. */
    public long cents() {
        return amount.movePointRight(2).longValueExact();
    }

    public Money plus(Money other)  { return new Money(amount.add(other.amount)); }
    public Money minus(Money other) { return new Money(amount.subtract(other.amount)); }

    public boolean isZero()       { return amount.signum() == 0; }
    public boolean isPositive()   { return amount.signum() > 0; }
    public boolean isNegative()   { return amount.signum() < 0; }

    public boolean isLessThan(Money other) { return compareTo(other) < 0; }

    public static Money min(Money a, Money b) { return a.compareTo(b) <= 0 ? a : b; }
    public static Money max(Money a, Money b) { return a.compareTo(b) >= 0 ? a : b; }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    /** Plain decimal string ("12.34"), never scientific notation ("1.234E+1"). */
    @Override
    public String toString() {
        return amount.toPlainString();
    }
}
