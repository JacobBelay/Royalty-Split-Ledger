package com.jacobbelay.royalty.collaborator;

import com.jacobbelay.royalty.money.Money;

/**
 * A person who earns royalties (a row of {@code collaborator}).
 *
 * <p>Deliberately holds <em>no</em> balance field. What they're owed is always derived from the ledger
 * ({@link com.jacobbelay.royalty.ledger.LedgerRepository#balance}), so there's one source of truth.
 *
 * @param payoutThreshold minimum payable balance before {@link com.jacobbelay.royalty.payout.PayoutService} sends money
 */
public record Collaborator(long id, String name, Money payoutThreshold) {
}
