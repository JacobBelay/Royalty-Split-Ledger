package com.jacobbelay.royalty.statement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * A fingerprint (SHA-256) of a statement's <em>content</em>, stored in {@code statement_import.content_sha256}.
 *
 * <p>Why: the idempotency key (source + externalRef) says "this is statement 2026-08", but not whether the
 * numbers are the same as last time. Comparing fingerprints tells a harmless retry (same hash, so return the
 * original result) from a corrected or tampered statement (different hash, so 409 Conflict, which a human
 * should review rather than us silently double-counting).
 *
 * <p><b>Canonical form</b>: lines are sorted by release id and amounts printed with exactly 2 decimals, so
 * the same statement always hashes the same regardless of line order or "5" vs "5.00" in the JSON.
 */
public final class StatementHasher {

    private StatementHasher() {
    }

    public static String sha256(List<StatementLine> lines) {
        StringBuilder canonical = new StringBuilder();
        lines.stream()
                .sorted(Comparator.comparingLong(StatementLine::releaseId))
                .forEach(line -> canonical.append(line.releaseId()).append('=').append(line.amount()).append('\n'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);   // 64 lowercase hex chars, fits CHAR(64)
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is guaranteed by the JDK", e);
        }
    }
}
