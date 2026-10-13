package com.jacobbelay.royalty.common;

/**
 * The request clashes with something already recorded, e.g. the same statement reference re-sent with
 * <em>different</em> numbers, or an Idempotency-Key reused for a different collaborator. HTTP 409.
 *
 * <p>Contrast with a true duplicate (same key, same content), which is <em>not</em> an error: idempotent
 * endpoints return the original result instead. See StatementImportService and PayoutService.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
