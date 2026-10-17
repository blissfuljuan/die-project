package com.blissfuljuan.dieapi.roster.controller;

import com.blissfuljuan.dieapi.auth.model.AppUserDetails;
import com.blissfuljuan.dieapi.roster.model.ResolutionChange;
import com.blissfuljuan.dieapi.roster.service.RosterImportQueryService;
import com.blissfuljuan.dieapi.roster.service.RosterImportService;
import com.blissfuljuan.dieapi.roster.view.CommitResult;
import com.blissfuljuan.dieapi.roster.view.ImportDetail;
import com.blissfuljuan.dieapi.roster.view.ImportView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.blissfuljuan.dieapi.common.exception.ApiException;
import com.blissfuljuan.dieapi.common.exception.ErrorCode;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/** Roster import endpoints. Fine-grained access is decided by AccessPolicy inside the service. */
@RestController
@RequestMapping("/api/v1")
public class RosterImportController {

    public record ResolutionsRequest(@NotEmpty List<@Valid ResolutionChange> resolutions) {}

    private final RosterImportService imports;
    private final RosterImportQueryService queries;

    public RosterImportController(RosterImportService imports, RosterImportQueryService queries) {
        this.imports = imports;
        this.queries = queries;
    }

    @PostMapping(path = "/classes/{classId}/roster-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportView> upload(@AuthenticationPrincipal AppUserDetails user,
                                             @PathVariable UUID classId,
                                             @RequestParam("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest(ErrorCode.UPLOAD_UNREADABLE, "The upload could not be read");
        }
        ImportView preview = imports.upload(user, classId, file.getOriginalFilename(), bytes);
        return ResponseEntity.created(URI.create("/api/v1/roster-imports/" + preview.id())).body(preview);
    }

    @GetMapping("/classes/{classId}/roster-imports")
    public List<ImportView> list(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID classId) {
        return queries.list(user, classId);
    }

    @GetMapping("/roster-imports/{id}")
    public ImportDetail detail(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                               @RequestParam(required = false) String action,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "100") int size) {
        return queries.detail(user, id, action, page, size);
    }

    @PutMapping("/roster-imports/{id}/resolutions")
    public ImportView resolve(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id,
                              @Valid @RequestBody ResolutionsRequest body) {
        return imports.resolve(user, id, body.resolutions());
    }

    /** Returns the one-time temporary credentials of newly created accounts: never cached. */
    @PostMapping("/roster-imports/{id}/commit")
    public ResponseEntity<CommitResult> commit(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(imports.commit(user, id));
    }

    @DeleteMapping("/roster-imports/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal AppUserDetails user, @PathVariable UUID id) {
        imports.cancel(user, id);
    }
}
