package com.jacobbelay.royalty.split;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One collaborator's ownership percentage of a release, e.g. (collaborator 7, 33.3333%).
 *
 * <p>Stored in {@code release_share} (see V1__ledger_schema.sql). Validated as a group
 * (all shares add to exactly 100) by {@link SplitCalculator#validateShares}.
 *
 * @param percent 0 &lt; percent &lt;= 100, up to 4 decimal places (matches NUMERIC(7,4) in the schema)
 */
public record Share(long collaboratorId, BigDecimal percent) { // record a java class to not have to write classic java methods for every single class

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100); // Base value 100 for to represent percentage

    public Share {
        Objects.requireNonNull(percent, "percent"); // Make Sure percent is not null

        if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("share percent must be > 0 and <= 100, got " + percent); // Ensuring the percentage of shares is in between 0 and 100 percent
        }
        if (percent.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("share percent allows at most 4 decimal places, got " + percent); // Enforcing ONLY 4 DECIMAL PLACES FOR PERCENTAGE???
        }
    }

    public static Share of(long collaboratorId, String percent) {
        return new Share(collaboratorId, new BigDecimal(percent));
    }
}
