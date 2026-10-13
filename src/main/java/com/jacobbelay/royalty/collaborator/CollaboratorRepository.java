package com.jacobbelay.royalty.collaborator;

import com.jacobbelay.royalty.money.Money;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * SQL for {@code collaborator}, including the <b>row locks</b> that prevent double payouts and double recoupment.
 *
 * <p><b>The race this solves.</b> Two payout requests for the same person arrive at once. Both read
 * "payable = $100", both decide to pay $100, both post. Now we've paid $200 against $100 owed.
 * The fix: before reading a balance to make a decision, take an exclusive lock on that person's
 * {@code collaborator} row with {@code SELECT ... FOR UPDATE}. The second transaction <em>waits</em> at the lock
 * until the first commits, then reads the updated balance ($0) and correctly refuses.
 *
 * <p>We lock the collaborator row rather than ledger lines because it's one well-known row per person:
 * every service that changes a person's money (payouts, advances, statement imports) locks the same row,
 * so they all serialize against each other. It's "pessimistic" locking: assume conflict and prevent it,
 * which suits money better than retrying after a clash.
 *
 * <p>Locks are held until the surrounding transaction ends, so callers must be {@code @Transactional}.
 */
@Repository
public class CollaboratorRepository {

    private static final RowMapper<Collaborator> MAPPER = (rs, rowNum) -> new Collaborator(
            rs.getLong("id"),
            rs.getString("name"),
            new Money(rs.getBigDecimal("payout_threshold")));

    private final JdbcClient jdbc;

    public CollaboratorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Collaborator create(String name, Money payoutThreshold) {
        return jdbc.sql("INSERT INTO collaborator (name, payout_threshold) VALUES (:name, :threshold) RETURNING id, name, payout_threshold")
                .param("name", name)
                .param("threshold", payoutThreshold.amount())
                .query(MAPPER)
                .single();
    }

    public Optional<Collaborator> findById(long id) {
        return jdbc.sql("SELECT id, name, payout_threshold FROM collaborator WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /** Locks one collaborator row until the transaction ends. Empty if the id doesn't exist. */
    public Optional<Collaborator> lockForUpdate(long id) {
        return jdbc.sql("SELECT id, name, payout_threshold FROM collaborator WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    /**
     * Locks several collaborators <b>in ascending id order</b>, which prevents deadlocks.
     *
     * <p>Deadlock example: import X locks Alice then wants Bob; import Y locked Bob and wants Alice. Each
     * waits forever for the other (Postgres detects this and kills one). If everyone always locks in the same
     * global order (lowest id first), a cycle is impossible. In Postgres, {@code ORDER BY ... FOR UPDATE}
     * locks rows in the sorted order.
     *
     * @return the locked collaborators, sorted by id (fewer than requested means some ids don't exist)
     */
    public List<Collaborator> lockAllForUpdate(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT id, name, payout_threshold FROM collaborator WHERE id IN (:ids) ORDER BY id FOR UPDATE")
                .param("ids", ids)
                .query(MAPPER)
                .list();
    }

    public long countExisting(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("SELECT COUNT(*) FROM collaborator WHERE id IN (:ids)")
                .param("ids", ids)
                .query(Long.class)
                .single();
    }
}
