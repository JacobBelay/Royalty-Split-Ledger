package com.jacobbelay.royalty.api;

import com.jacobbelay.royalty.api.ApiDtos.CreateReleaseRequest;
import com.jacobbelay.royalty.api.ApiDtos.IdResponse;
import com.jacobbelay.royalty.release.ReleaseService;
import com.jacobbelay.royalty.split.Share;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** POST /api/releases: create a release with its ownership split. Rules live in {@link ReleaseService}. */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releases;

    public ReleaseController(ReleaseService releases) {
        this.releases = releases;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdResponse create(@Valid @RequestBody CreateReleaseRequest request) {
        List<Share> shares = request.shares().stream()
                .map(s -> new Share(s.collaboratorId(), s.percent()))
                .toList();
        return new IdResponse(releases.createRelease(request.title(), shares));
    }
}
