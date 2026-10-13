package com.jacobbelay.royalty.advance;

import com.jacobbelay.royalty.collaborator.CollaboratorRepository;
import com.jacobbelay.royalty.common.NotFoundException;
import com.jacobbelay.royalty.ledger.EntrySource;
import com.jacobbelay.royalty.ledger.LedgerRepository;
import com.jacobbelay.royalty.ledger.Postings;
import com.jacobbelay.royalty.money.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records money fronted to a collaborator. Later statements pay it back through
 * {@link com.jacobbelay.royalty.recoupment.RecoupmentCalculator}.
 *
 * <p>Two writes, one transaction: the business record ({@code advance} row) and the accounting record
 * ({@link Postings#advanceIssued}: debit ADVANCE_RECEIVABLE, credit CASH), linked by {@code advance_id}.
 *
 * <p>It locks the collaborator row first, like every money-moving service (see
 * {@link CollaboratorRepository}), so an advance can't interleave with a statement import that's
 * mid-way through computing that person's recoupment.
 */
@Service
public class AdvanceService {

    private final CollaboratorRepository collaborators;
    private final LedgerRepository ledger;
    private final JdbcClient jdbc;

    public AdvanceService(CollaboratorRepository collaborators, LedgerRepository ledger, JdbcClient jdbc) {
        this.collaborators = collaborators;
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    /** @return the new advance id */
    @Transactional
    public long issue(long collaboratorId, Money amount) {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("advance amount must be positive");
        }
        collaborators.lockForUpdate(collaboratorId)
                .orElseThrow(() -> new NotFoundException("collaborator " + collaboratorId + " not found"));

        long advanceId = jdbc.sql("INSERT INTO advance (collaborator_id, amount) VALUES (:c, :amount) RETURNING id")
                .param("c", collaboratorId)
                .param("amount", amount.amount())
                .query(Long.class)
                .single();

        ledger.post(Postings.advanceIssued(collaboratorId, amount), EntrySource.advance(advanceId));
        return advanceId;
    }
}
