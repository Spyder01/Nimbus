package com.spyder01.nimbus.backend.apps.dto

import com.spyder01.nimbus.backend.apps.entities.App
import com.spyder01.nimbus.backend.apps.entities.AppSpec
import com.spyder01.nimbus.backend.apps.entities.AppState
import java.time.Instant
import java.util.UUID

data class CreateAppRequest(val name: String?)

data class RenameAppRequest(val name: String?)

data class SaveDraftRequest(
    val nodes: List<GraphNode> = emptyList(),
    val edges: List<GraphEdge> = emptyList(),
    /** The draft revision this edit was based on; a mismatch means someone else saved in between. */
    val baseRevision: Long? = null,
)

data class SaveVersionRequest(val note: String? = null)

data class RestoreVersionRequest(val baseRevision: Long? = null)

data class AppSummary(
    val id: UUID,
    val name: String,
    val state: AppState,
    val componentCount: Int,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    /** The queued or running deployment, if any. */
    val activeDeployment: DeploymentDto? = null,
) {
    companion object {
        fun from(app: App, active: DeploymentDto? = null) =
            AppSummary(requireNotNull(app.id), app.name, app.state, app.componentCount, app.createdAt, app.updatedAt, active)
    }
}

data class DraftDto(
    val revision: Long,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val problems: List<Problem>,
    val updatedAt: Instant?,
)

data class AppDetail(
    val id: UUID,
    val name: String,
    val state: AppState,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val draft: DraftDto,
    val activeDeployment: DeploymentDto? = null,
    val latestDeployment: DeploymentDto? = null,
)

data class VersionSummary(
    val revision: Long,
    val note: String?,
    val nodeCount: Int,
    val edgeCount: Int,
    val createdBy: UUID?,
    val createdAt: Instant?,
) {
    companion object {
        fun from(spec: AppSpec) =
            VersionSummary(spec.revision, spec.note, spec.nodes.size, spec.edges.size, spec.createdBy, spec.createdAt)
    }
}

data class VersionDetail(
    val revision: Long,
    val note: String?,
    val createdBy: UUID?,
    val createdAt: Instant?,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
) {
    companion object {
        fun from(spec: AppSpec) =
            VersionDetail(spec.revision, spec.note, spec.createdBy, spec.createdAt, spec.nodes, spec.edges)
    }
}

/** Result of "save version": [created] is false when the draft matched the latest version. */
data class SaveVersionResult(val created: Boolean, val version: VersionSummary)
