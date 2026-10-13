package com.jacobbelay.royalty.payout;

import com.jacobbelay.royalty.collaborator.Collaborator;
import com.jacobbelay.royalty.collaborator.CollaboratorRepository;
import com.jacobbelay.royalty.common.BusinessRuleException;
import com.jacobbelay.royalty.common.ConflictException;
import com.jacobbelay.royalty.common.NotFoundException;
import com.jacobbelay.royalty.ledger.Account;
import com.jacobbelay.royalty.ledger.EntrySource;
import com.jacobbelay.royalty.ledger.LedgerRepository;
import com.jacobbelay.royalty.ledger.Postings;
import com.jacobbelay.royalty.money.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Pays a collaborator their full payable balance, <b>at most once per request and never more than owed</b>.
 *
 * <p>Three protections, each stopping a different failure:
 * <ol>
 *   <li><b>Row lock</b> ({@link CollaboratorRepository#lockForUpdate}) stops <em>concurrent</em> requests from
 *       both reading the same balance and both paying it. The second waits, then sees a balance of 0.
 *       Tested by PayoutConcurrencyIT with many simultaneous threads.</li>
 *   <li><b>Idempotency key</b> (client-supplied, UNIQUE in {@code payout}) stops <em>retried</em> requests from
 *       paying again: the same key returns the original payout. Checked after the lock, so a retry that
 *       races the original request still sees it.</li>
 *   <li><b>Payout threshold</b> stops tiny transfers: if the balance is below the collaborator's threshold
 *       (default $50.00), nothing is paid and the money keeps accumulating.</li>
 * </ol>
 * The ledger side is {@link Postings#payoutSent}: debit ROYALTY_PAYABLE (we owe less), credit CASH.
 */
@Service
public class PayoutService {

    private final CollaboratorRepository collaborators;
    private final LedgerRepository ledger;
    private final JdbcClient jdbc;

    public PayoutService(CollaboratorRepository collaborators, LedgerRepository ledger, JdbcClient jdbc) {
        this.collaborators = collaborators;
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    @Transactional
    public PayoutResult requestPayout(long collaboratorId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("an Idempotency-Key is required so retries can't pay twice");
        }

        // 1. Lock first. Everything below runs with this collaborator's money frozen for other transactions.
        Collaborator collaborator = collaborators.lockForUpdate(collaboratorId)
                .orElseThrow(() -> new NotFoundException("collaborator " + collaboratorId + " not found"));

        // 2. Seen this key before? Return the original instead of paying again.
        Optional<PayoutResult> previous = findByKey(idempotencyKey);
        if (previous.isPresent()) {
            if (previous.get().collaboratorId() != collaboratorId) {
                throw new ConflictException("Idempotency-Key already used for a different collaborator");
            }
            return previous.get();
        }

        // 3. Current balance (safe to trust: nobody else can post for this collaborator until we commit).
        Money payable = ledger.balance(Account.payable(collaboratorId));
        if (!payable.isPositive() || payable.isLessThan(collaborator.payoutThreshold())) {
            throw new BusinessRuleException("payable balance " + payable + " is below the payout threshold "
                    + collaborator.payoutThreshold());
        }

        // 4. Record the payout and its ledger entry together.
        long payoutId = jdbc.sql("INSERT INTO payout (collaborator_id, amount, idempotency_key) VALUES (:c, :amount, :key) RETURNING id")
                .param("c", collaboratorId)
                .param("amount", payable.amount())
                .param("key", idempotencyKey)
                .query(Long.class)
                .single();
        ledger.post(Postings.payoutSent(collaboratorId, payable), EntrySource.payout(payoutId));
        return new PayoutResult(payoutId, collaboratorId, payable, false);
    }

    private Optional<PayoutResult> findByKey(String key) {
        return jdbc.sql("SELECT id, collaborator_id, amount FROM payout WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, rowNum) -> new PayoutResult(rs.getLong("id"), rs.getLong("collaborator_id"),
                        new Money(rs.getBigDecimal("amount")), true))
                .optional();
    }
}
