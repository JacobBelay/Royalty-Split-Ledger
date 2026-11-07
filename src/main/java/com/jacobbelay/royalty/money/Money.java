package com.jacobbelay.royalty.money; // Package Declaration

import java.math.BigDecimal; // Importing Big Decimal
import java.math.RoundingMode; // Importing Rounding Mode
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
 * 
 * 
 */

// Parameters Force Money to only take a big decimal object
public record Money(BigDecimal amount) implements Comparable<Money> { // Use record to enforce the immutablility of the money coming in

    public static final Money ZERO = new Money(BigDecimal.ZERO); // Stores Big Money Zero in an Object, Creating the starter value and the lower boundary

    /** Compact constructor: runs on every construction, so no Money with the wrong scale can exist. */
    public Money {
        Objects.requireNonNull(amount, "amount"); // Throw Null Pointer Exception if amount Returns a null value
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);  //Set Scale forces the two decimal places, throws ArithmeticException on 1.005 to ensure the money does not get lost through the rest of the proccessing of the app
    }

    /** {@code Money.of("12.34")}. Strings avoid the double-literal trap: {@code new BigDecimal(0.1)} is 0.1000000000000000055... */
    public static Money of(String amount) { // Converts Input from string to big decimal
        return new Money(new BigDecimal(amount));
    }

    /** Build from whole cents, e.g. {@code ofCents(1234)} is 12.34. Used by the split math, which works in integer cents. */
    public static Money ofCents(long cents) {
        return new Money(BigDecimal.valueOf(cents, 2));
    }

    /** The amount as whole cents, e.g. 12.34 gives 1234. {@code longValueExact} throws rather than overflow silently. */
    public long cents() {
        return amount.movePointRight(2).longValueExact(); // Throw Arthi Exception If the long doesnt fit
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
