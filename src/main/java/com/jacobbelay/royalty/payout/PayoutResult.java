package com.jacobbelay.royalty.payout;

import com.jacobbelay.royalty.money.Money;

/**
 * @param duplicate true if this Idempotency-Key was already used; the original payout is returned and nothing new is paid
 */
public record PayoutResult(long payoutId, long collaboratorId, Money amount, boolean duplicate) {
}
