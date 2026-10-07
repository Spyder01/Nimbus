package com.spyder01.nimbus.backend.apps.controllers

import com.spyder01.nimbus.backend.apps.dto.AppDetail
import com.spyder01.nimbus.backend.apps.dto.AppSummary
import com.spyder01.nimbus.backend.apps.dto.CreateAppRequest
import com.spyder01.nimbus.backend.apps.dto.DraftDto
import com.spyder01.nimbus.backend.apps.dto.ImportAppRequest
import com.spyder01.nimbus.backend.apps.dto.RenameAppRequest
import com.spyder01.nimbus.backend.apps.dto.RestoreVersionRequest
import com.spyder01.nimbus.backend.apps.dto.SaveDraftRequest
import com.spyder01.nimbus.backend.apps.dto.SaveVersionRequest
import com.spyder01.nimbus.backend.apps.dto.SaveVersionResult
import com.spyder01.nimbus.backend.apps.dto.VersionDetail
import com.spyder01.nimbus.backend.apps.dto.VersionSummary
import com.spyder01.nimbus.backend.apps.services.AppService
import com.spyder01.nimbus.backend.shared.userId
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/apps")
class AppController(
    private val service: AppService,
) {
    @GetMapping
    fun list(@AuthenticationPrincipal user: OAuth2User): List<AppSummary> = service.list(user.userId())

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal user: OAuth2User, @RequestBody req: CreateAppRequest): AppDetail =
        service.create(user.userId(), req.name)

    /** Creates an app from YAML (the format the canvas exports). 400 `invalid_yaml` lists problems with line numbers. */
    @PostMapping("/import")
    @ResponseStatus(HttpStatus.CREATED)
    fun import(@AuthenticationPrincipal user: OAuth2User, @RequestBody req: ImportAppRequest): AppDetail =
        service.importApp(user.userId(), req.name, req.yaml)

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal user: OAuth2User, @PathVariable id: UUID): AppDetail =
        service.get(user.userId(), id)

    @PatchMapping("/{id}")
    fun rename(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable id: UUID,
        @RequestBody req: RenameAppRequest,
    ): AppSummary = service.rename(user.userId(), id, req.name)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal user: OAuth2User, @PathVariable id: UUID) =
        service.delete(user.userId(), id)

    /** Autosave: replaces the whole draft graph. 409 if [SaveDraftRequest.baseRevision] is stale. */
    @PutMapping("/{id}/draft")
    fun saveDraft(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable id: UUID,
        @RequestBody req: SaveDraftRequest,
    ): DraftDto = service.saveDraft(user.userId(), id, req)

    @GetMapping("/{id}/versions")
    fun versions(@AuthenticationPrincipal user: OAuth2User, @PathVariable id: UUID): List<VersionSummary> =
        service.versions(user.userId(), id)

    @GetMapping("/{id}/versions/{revision}")
    fun version(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable id: UUID,
        @PathVariable revision: Long,
    ): VersionDetail = service.version(user.userId(), id, revision)

    /** 201 when a version was created, 200 when the draft is identical to the latest version. */
    @PostMapping("/{id}/versions")
    fun saveVersion(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable id: UUID,
        @RequestBody(required = false) req: SaveVersionRequest?,
    ): ResponseEntity<SaveVersionResult> {
        val result = service.saveVersion(user.userId(), id, req?.note)
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(result)
    }

    @PostMapping("/{id}/versions/{revision}/restore")
    fun restore(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable id: UUID,
        @PathVariable revision: Long,
        @RequestBody(required = false) req: RestoreVersionRequest?,
    ): DraftDto = service.restore(user.userId(), id, revision, req?.baseRevision)
}
