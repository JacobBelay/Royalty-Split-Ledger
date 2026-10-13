package com.jacobbelay.royalty.release;

import com.jacobbelay.royalty.split.Share;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * SQL for {@code release} and {@code release_share}. Shares are read back as {@link Share}s ready to hand
 * straight to {@link com.jacobbelay.royalty.split.SplitCalculator}.
 */
@Repository
public class ReleaseRepository {

    private final JdbcClient jdbc;

    public ReleaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long create(String title) {
        return jdbc.sql("INSERT INTO release (title) VALUES (:title) RETURNING id")
                .param("title", title)
                .query(Long.class)
                .single();
    }

    public void addShare(long releaseId, Share share) {
        jdbc.sql("INSERT INTO release_share (release_id, collaborator_id, percent) VALUES (:releaseId, :collaboratorId, :percent)")
                .param("releaseId", releaseId)
                .param("collaboratorId", share.collaboratorId())
                .param("percent", share.percent())
                .update();
    }

    /**
     * Shares ordered by collaborator id. A stable order matters: SplitCalculator's output order follows its
     * input, and deterministic output keeps statement results reproducible.
     * Empty list means the release doesn't exist (every real release has at least one share).
     */
    public List<Share> sharesFor(long releaseId) {
        return jdbc.sql("SELECT collaborator_id, percent FROM release_share WHERE release_id = :id ORDER BY collaborator_id")
                .param("id", releaseId)
                .query((rs, rowNum) -> new Share(rs.getLong("collaborator_id"), rs.getBigDecimal("percent")))
                .list();
    }
}
