package com.jacobbelay.royalty.release;

import com.jacobbelay.royalty.collaborator.CollaboratorRepository;
import com.jacobbelay.royalty.common.NotFoundException;
import com.jacobbelay.royalty.split.Share;
import com.jacobbelay.royalty.split.SplitCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Creates a release together with its ownership shares, all or nothing.
 *
 * <p>Validation happens up front, so a bad split never reaches the table and every statement split later
 * can trust that shares sum to 100%:
 * <ol>
 *   <li>{@link SplitCalculator#validateShares}: non-empty, no duplicates, exactly 100%;</li>
 *   <li>every collaborator exists (the FK would also catch this, but with a less helpful message).</li>
 * </ol>
 * {@code @Transactional} means if any share insert fails, the release row is rolled back too.
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releases;
    private final CollaboratorRepository collaborators;

    public ReleaseService(ReleaseRepository releases, CollaboratorRepository collaborators) {
        this.releases = releases;
        this.collaborators = collaborators;
    }

    @Transactional
    public long createRelease(String title, List<Share> shares) {
        SplitCalculator.validateShares(shares);
        List<Long> ids = shares.stream().map(Share::collaboratorId).toList();
        if (collaborators.countExisting(ids) != ids.size()) {
            throw new NotFoundException("one or more collaborators in " + ids + " do not exist");
        }
        long releaseId = releases.create(title);
        shares.forEach(share -> releases.addShare(releaseId, share));
        return releaseId;
    }
}
