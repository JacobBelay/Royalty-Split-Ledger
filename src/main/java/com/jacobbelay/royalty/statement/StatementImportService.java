package com.jacobbelay.royalty.statement;

import com.jacobbelay.royalty.collaborator.CollaboratorRepository;
import com.jacobbelay.royalty.common.ConflictException;
import com.jacobbelay.royalty.common.NotFoundException;
import com.jacobbelay.royalty.ledger.Account;
import com.jacobbelay.royalty.ledger.EntrySource;
import com.jacobbelay.royalty.ledger.LedgerRepository;
import com.jacobbelay.royalty.ledger.Postings;
import com.jacobbelay.royalty.money.Money;
import com.jacobbelay.royalty.recoupment.RecoupmentCalculator;
import com.jacobbelay.royalty.release.ReleaseRepository;
import com.jacobbelay.royalty.split.Allocation;
import com.jacobbelay.royalty.split.Share;
import com.jacobbelay.royalty.split.SplitCalculator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns a distributor statement into ledger entries, <b>exactly once</b>. This class is where every other
 * piece meets; reading it top to bottom is the best tour of the project.
 *
 * <p><b>Pipeline</b> (one database transaction, so it all commits or none of it does):
 * <ol>
 *   <li><b>Claim the idempotency key.</b> {@code INSERT ... ON CONFLICT DO NOTHING RETURNING id} into
 *       {@code statement_import}. If a row for (source, externalRef) already exists we get no id back and
 *       go to {@link #handleDuplicate}: same content hash returns the original result; different hash throws 409.
 *       Why ON CONFLICT instead of "SELECT then INSERT": two identical requests arriving together would
 *       both pass the SELECT. The unique index makes the second INSERT wait for the first transaction, then
 *       see the committed row. The database arbitrates the race; no Java lock needed.</li>
 *   <li><b>Split</b> each line by its release's shares ({@link SplitCalculator}, remainder rule, every cent kept),
 *       and add up each collaborator's earnings across lines.</li>
 *   <li><b>Lock</b> the affected collaborators in id order ({@link CollaboratorRepository#lockAllForUpdate}),
 *       so a concurrent import or advance for the same people waits instead of reading a stale advance balance.</li>
 *   <li><b>Recoup</b>: for each collaborator, read their outstanding advance <em>after</em> locking, and split
 *       earnings into recouped vs payable ({@link RecoupmentCalculator}).</li>
 *   <li><b>Post</b> one balanced entry ({@link Postings#statementReceived}) linked to the import row.</li>
 * </ol>
 */
@Service
public class StatementImportService {

    private final JdbcClient jdbc;
    private final ReleaseRepository releases;
    private final CollaboratorRepository collaborators;
    private final LedgerRepository ledger;

    public StatementImportService(JdbcClient jdbc, ReleaseRepository releases,
                                  CollaboratorRepository collaborators, LedgerRepository ledger) {
        this.jdbc = jdbc;
        this.releases = releases;
        this.collaborators = collaborators;
        this.ledger = ledger;
    }

    @Transactional
    public StatementImportResult importStatement(StatementImportCommand command) {
        validate(command);
        Money gross = command.lines().stream().map(StatementLine::amount).reduce(Money.ZERO, Money::plus);
        String hash = StatementHasher.sha256(command.lines());

        // ---- 1. Claim the idempotency key ---------------------------------------------------------------
        Optional<Long> claimed = jdbc.sql("""
                        INSERT INTO statement_import (source, external_ref, content_sha256, gross)
                        VALUES (:source, :ref, :hash, :gross)
                        ON CONFLICT (source, external_ref) DO NOTHING
                        RETURNING id
                        """)
                .param("source", command.source())
                .param("ref", command.externalRef())
                .param("hash", hash)
                .param("gross", gross.amount())
                .query(Long.class)
                .optional();
        if (claimed.isEmpty()) {
            return handleDuplicate(command, hash);
        }
        long importId = claimed.get();

        // ---- 2. Split every line, then total per collaborator ------------------------------------------
        // TreeMap keeps collaborators in ascending id order: the same order we lock in (step 3) and report in.
        Map<Long, Money> earnedByCollaborator = new TreeMap<>();
        for (StatementLine line : command.lines()) {
            List<Share> shares = releases.sharesFor(line.releaseId());
            if (shares.isEmpty()) {
                // Throwing inside @Transactional rolls back the statement_import row too, so the key is freed for a retry.
                throw new NotFoundException("release " + line.releaseId() + " not found");
            }
            for (Allocation allocation : SplitCalculator.split(line.amount(), shares)) {
                earnedByCollaborator.merge(allocation.collaboratorId(), allocation.amount(), Money::plus);
            }
        }

        // ---- 3. Lock everyone we're about to credit -----------------------------------------------------
        collaborators.lockAllForUpdate(earnedByCollaborator.keySet());

        // ---- 4. Recoup advances (balances read AFTER the locks, so they're current) ---------------------
        List<Postings.Earning> earnings = new ArrayList<>();
        earnedByCollaborator.forEach((collaboratorId, earned) -> {
            Money outstanding = ledger.balance(Account.advanceReceivable(collaboratorId));
            RecoupmentCalculator.Recoupment r = RecoupmentCalculator.apply(earned, outstanding);
            earnings.add(new Postings.Earning(collaboratorId, r.recouped(), r.payable()));
        });

        // ---- 5. Post one balanced entry (skipped for an all-zero statement: an entry needs non-zero lines) -
        if (gross.isPositive()) {
            String description = "Statement " + command.source() + " / " + command.externalRef();
            ledger.post(Postings.statementReceived(description, gross, earnings), EntrySource.statement(importId));
        }
        return new StatementImportResult(importId, false, gross, earnings);
    }

    /**
     * Same (source, externalRef) seen before. Same content means a retry: answer with the original numbers,
     * rebuilt from the ledger. Different content means someone changed the statement: refuse loudly.
     */
    private StatementImportResult handleDuplicate(StatementImportCommand command, String hash) {
        record Existing(long id, String hash, Money gross) {
        }
        Existing existing = jdbc.sql("SELECT id, content_sha256, gross FROM statement_import WHERE source = :source AND external_ref = :ref")
                .param("source", command.source())
                .param("ref", command.externalRef())
                .query((rs, rowNum) -> new Existing(rs.getLong("id"), rs.getString("content_sha256"), new Money(rs.getBigDecimal("gross"))))
                .single();
        if (!existing.hash().equals(hash)) {
            throw new ConflictException("statement " + command.source() + " / " + command.externalRef()
                    + " was already imported with different content; post a correcting statement under a new reference");
        }
        return new StatementImportResult(existing.id(), true, existing.gross(), ledger.earningsForStatement(existing.id()));
    }

    private static void validate(StatementImportCommand command) {
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new IllegalArgumentException("a statement needs at least one line");
        }
        Set<Long> seen = new HashSet<>();
        for (StatementLine line : command.lines()) {
            if (line.amount().isNegative()) {
                throw new IllegalArgumentException("line amounts cannot be negative (release " + line.releaseId() + ")");
            }
            if (!seen.add(line.releaseId())) {
                throw new IllegalArgumentException("release " + line.releaseId() + " appears twice; combine it into one line");
            }
        }
    }
}
