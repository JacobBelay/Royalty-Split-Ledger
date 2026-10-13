package com.jacobbelay.royalty.common;

/**
 * A referenced collaborator/release doesn't exist. Mapped to HTTP 404 by
 * {@link com.jacobbelay.royalty.api.GlobalExceptionHandler}.
 *
 * <p>The three exceptions in this package describe <em>what went wrong in business terms</em>; only the
 * handler knows about HTTP. That keeps services reusable from tests, jobs or a CLI.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
