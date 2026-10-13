package com.jacobbelay.royalty.common;

/**
 * The request is well-formed but a business rule says no, e.g. a payable balance below the payout
 * threshold. HTTP 422 (Unprocessable Content): "I understood you, but I won't do it."
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
