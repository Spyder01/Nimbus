package com.spyder01.nimbus.backend.apps.dto

import com.spyder01.nimbus.backend.apps.entities.AppState
import com.spyder01.nimbus.backend.apps.entities.DeploymentState
import com.spyder01.nimbus.backend.apps.entities.TaskState
import java.time.Instant
import java.util.UUID

/** One container's deployment within a [DeploymentDto]. */
data class DeploymentTaskDto(
    val id: UUID,
    val nodeId: String,
    val name: String,
    /** Position in the deployment order (0 = deployed first). */
    val ordinal: Int,
    /** Tasks in the same layer don't depend on each other. */
    val layer: Int,
    val state: TaskState,
    val attempts: Int,
    val error: String?,
    /** Names of the containers this one waits for. */
    val dependsOn: List<String>,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    /** Where a public container can be opened, once it is deployed. */
    val url: String? = null,
)

data class DeploymentDto(
    val id: UUID,
    val appId: UUID,
    /** The saved version this deployment runs. */
    val versionRevision: Long?,
    val state: DeploymentState,
    /** A cancel was requested while it was running; it finishes as CANCELLED once the running tasks stop. */
    val cancelRequested: Boolean,
    val error: String?,
    val requestedBy: UUID?,
    val createdAt: Instant?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val tasksTotal: Int,
    val tasksSucceeded: Int,
    /** Per-container progress. Included while the deployment is active and when fetching one deployment. */
    val tasks: List<DeploymentTaskDto>?,
)

/** The deployment list plus the app's authoritative state, so one poll answers everything the UI shows. */
data class DeploymentList(
    val appState: AppState,
    val deployments: List<DeploymentDto>,
    /** Where the running app's public containers can be opened; empty unless the app is running. */
    val publicUrls: List<PublicUrlDto> = emptyList(),
)
