package com.spyder01.nimbus.backend.apps.services

import com.spyder01.nimbus.backend.apps.dto.AppDetail
import com.spyder01.nimbus.backend.apps.dto.AppSummary
import com.spyder01.nimbus.backend.apps.dto.DraftDto
import com.spyder01.nimbus.backend.apps.dto.GraphEdge
import com.spyder01.nimbus.backend.apps.dto.GraphNode
import com.spyder01.nimbus.backend.apps.dto.SaveDraftRequest
import com.spyder01.nimbus.backend.apps.dto.SaveVersionResult
import com.spyder01.nimbus.backend.apps.dto.VersionDetail
import com.spyder01.nimbus.backend.apps.dto.VersionSummary
import com.spyder01.nimbus.backend.apps.entities.App
import com.spyder01.nimbus.backend.apps.entities.AppSpec
import com.spyder01.nimbus.backend.apps.entities.DeploymentState
import com.spyder01.nimbus.backend.apps.entities.SpecKind
import com.spyder01.nimbus.backend.apps.repositories.AppRepository
import com.spyder01.nimbus.backend.apps.repositories.AppSpecRepository
import com.spyder01.nimbus.backend.apps.repositories.DeploymentRepository
import com.spyder01.nimbus.backend.shared.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AppService(
    private val apps: AppRepository,
    private val specs: AppSpecRepository,
    private val deployments: DeploymentRepository,
    private val views: DeploymentViews,
    private val validator: GraphValidator,
    private val hasher: GraphHasher,
    private val yamlImporter: AppYamlImporter,
) {
    companion object {
        const val MAX_APPS_PER_USER = 50
        const val MAX_SAVED_VERSIONS = 50
        private const val MAX_NAME = 60
        private const val MAX_NOTE = 200
    }

    // ---- apps ----

    @Transactional(readOnly = true)
    fun list(owner: UUID): List<AppSummary> {
        val all = apps.findAllByOwnerIdOrderByUpdatedAtDesc(owner)
        if (all.isEmpty()) return emptyList()
        val active = views.toDtos(deployments.findAllByAppIdInAndStateIn(all.map { requireNotNull(it.id) }, DeploymentState.ACTIVE))
            .associateBy { it.appId }
        return all.map { AppSummary.from(it, active[it.id]) }
    }

    @Transactional
    fun create(owner: UUID, rawName: String?, nodes: List<GraphNode> = emptyList(), edges: List<GraphEdge> = emptyList()): AppDetail {
        val name = validName(rawName)
        if (apps.countByOwnerId(owner) >= MAX_APPS_PER_USER) {
            throw ApiException(HttpStatus.CONFLICT, "app_limit", "You can have up to $MAX_APPS_PER_USER apps")
        }
        if (apps.existsByOwnerIdAndNameIgnoreCase(owner, name)) throw nameTaken()

        val app = apps.save(App(ownerId = owner, name = name, componentCount = nodes.size))
        val draft = specs.save(
            AppSpec(
                appId = requireNotNull(app.id),
                kind = SpecKind.DRAFT,
                revision = 0,
                nodes = nodes,
                edges = edges,
                contentHash = hasher.hash(nodes, edges),
                createdBy = owner,
            ),
        )
        return detail(app, draft)
    }

    /** A new app whose draft is the graph described by [yaml] (see [AppYamlImporter] for the format). */
    @Transactional
    fun importApp(owner: UUID, rawName: String?, yaml: String?): AppDetail {
        val parsed = yamlImporter.parse(yaml.orEmpty())
        validator.requireWellFormed(parsed.nodes, parsed.edges)
        return create(owner, rawName, parsed.nodes, parsed.edges)
    }

    @Transactional(readOnly = true)
    fun get(owner: UUID, id: UUID): AppDetail {
        val app = owned(owner, id)
        return detail(app, draftOf(app))
    }

    @Transactional
    fun rename(owner: UUID, id: UUID, rawName: String?): AppSummary {
        val app = owned(owner, id)
        val name = validName(rawName)
        if (apps.existsByOwnerIdAndNameIgnoreCaseAndIdNot(owner, name, id)) throw nameTaken()
        app.name = name
        return AppSummary.from(app)
    }

    /** Specs go with the app (ON DELETE CASCADE). Once deploying exists this must tear down the cluster first. */
    @Transactional
    fun delete(owner: UUID, id: UUID) {
        val app = owned(owner, id)
        if (deployments.existsByAppIdAndStateIn(requireNotNull(app.id), DeploymentState.ACTIVE)) {
            throw ApiException(HttpStatus.CONFLICT, "deployment_active", "Cancel the running deployment before deleting this app")
        }
        apps.delete(app)
    }

    // ---- draft ----

    @Transactional
    fun saveDraft(owner: UUID, id: UUID, req: SaveDraftRequest): DraftDto {
        val app = owned(owner, id)
        val draft = draftOf(app)
        val base = req.baseRevision
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "base_revision_required", "baseRevision is required")
        if (base != draft.revision) throw conflict(draft)
        validator.requireWellFormed(req.nodes, req.edges)
        return replaceDraft(app, draft, req.nodes, req.edges)
    }

    // ---- saved versions ----

    @Transactional(readOnly = true)
    fun versions(owner: UUID, id: UUID): List<VersionSummary> {
        val app = owned(owner, id)
        return specs.findAllByAppIdAndKindOrderByRevisionDesc(requireNotNull(app.id), SpecKind.SAVED)
            .map(VersionSummary::from)
    }

    @Transactional(readOnly = true)
    fun version(owner: UUID, id: UUID, revision: Long): VersionDetail =
        VersionDetail.from(savedOf(owned(owner, id), revision))

    @Transactional
    fun saveVersion(owner: UUID, id: UUID, rawNote: String?): SaveVersionResult {
        val app = lockedOwned(owner, id)
        val appId = requireNotNull(app.id)
        val draft = draftOf(app)

        val latest = specs.findFirstByAppIdAndKindOrderByRevisionDesc(appId, SpecKind.SAVED)
        if (latest != null && latest.contentHash == draft.contentHash) {
            return SaveVersionResult(created = false, version = VersionSummary.from(latest))
        }

        app.versionSeq += 1
        val saved = specs.save(
            AppSpec(
                appId = appId,
                kind = SpecKind.SAVED,
                revision = app.versionSeq.toLong(),
                nodes = draft.nodes,
                edges = draft.edges,
                contentHash = draft.contentHash,
                note = rawNote?.trim()?.take(MAX_NOTE)?.ifEmpty { null },
                createdBy = owner,
                schemaVersion = draft.schemaVersion,
            ),
        )
        prune(appId)
        return SaveVersionResult(created = true, version = VersionSummary.from(saved))
    }

    /** Copies a saved version back into the draft (the version itself is never changed). */
    @Transactional
    fun restore(owner: UUID, id: UUID, revision: Long, baseRevision: Long?): DraftDto {
        val app = lockedOwned(owner, id)
        val draft = draftOf(app)
        if (baseRevision != null && baseRevision != draft.revision) throw conflict(draft)
        val version = savedOf(app, revision)
        return replaceDraft(app, draft, version.nodes, version.edges)
    }

    // ---- helpers ----

    private fun replaceDraft(
        app: App,
        draft: AppSpec,
        nodes: List<com.spyder01.nimbus.backend.apps.dto.GraphNode>,
        edges: List<com.spyder01.nimbus.backend.apps.dto.GraphEdge>,
    ): DraftDto {
        draft.nodes = nodes
        draft.edges = edges
        draft.contentHash = hasher.hash(nodes, edges)
        draft.revision += 1
        app.componentCount = nodes.size
        app.updatedAt = java.time.Instant.now() // an unchanged component count must still bump "updated"
        return draftDto(draft)
    }

    private fun prune(appId: UUID) {
        val all = specs.findAllByAppIdAndKindOrderByRevisionDesc(appId, SpecKind.SAVED)
        if (all.size > MAX_SAVED_VERSIONS) {
            // A version a deployment points at is part of the deploy record, so it is never pruned.
            val deployed = deployments.specIdsOf(appId).toSet()
            specs.deleteAll(all.drop(MAX_SAVED_VERSIONS).filter { it.id !in deployed })
        }
    }

    private fun owned(owner: UUID, id: UUID): App =
        apps.findByIdAndOwnerId(id, owner) ?: throw notFound()

    private fun lockedOwned(owner: UUID, id: UUID): App =
        apps.findForUpdate(id, owner) ?: throw notFound()

    private fun draftOf(app: App): AppSpec =
        specs.findByAppIdAndKind(requireNotNull(app.id), SpecKind.DRAFT)
            ?: error("App ${app.id} has no draft")

    private fun savedOf(app: App, revision: Long): AppSpec =
        specs.findByAppIdAndKindAndRevision(requireNotNull(app.id), SpecKind.SAVED, revision)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "version_not_found", "Version not found")

    private fun draftDto(draft: AppSpec) =
        DraftDto(draft.revision, draft.nodes, draft.edges, validator.problems(draft.nodes, draft.edges), draft.updatedAt)

    private fun detail(app: App, draft: AppSpec): AppDetail {
        val appId = requireNotNull(app.id)
        val active = deployments.findFirstByAppIdAndStateInOrderByCreatedAtDesc(appId, DeploymentState.ACTIVE)
        val latest = deployments.findFirstByAppIdOrderByCreatedAtDesc(appId)
        val dtos = views.toDtos(listOfNotNull(active, latest).distinctBy { it.id }).associateBy { it.id }
        return AppDetail(
            appId, app.name, app.state, app.createdAt, app.updatedAt, draftDto(draft),
            activeDeployment = active?.let { dtos[it.id] }, latestDeployment = latest?.let { dtos[it.id] },
        )
    }

    private fun validName(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name.length > MAX_NAME) {
            throw ApiException(HttpStatus.BAD_REQUEST, "invalid_name", "Name must be 1-$MAX_NAME characters")
        }
        return name
    }

    private fun nameTaken() = ApiException(HttpStatus.CONFLICT, "name_taken", "You already have an app with that name")

    private fun notFound() = ApiException(HttpStatus.NOT_FOUND, "app_not_found", "App not found")

    private fun conflict(draft: AppSpec) = ApiException(
        HttpStatus.CONFLICT, "conflict", "The draft was changed elsewhere; reload it",
        mapOf("currentRevision" to draft.revision),
    )
}
