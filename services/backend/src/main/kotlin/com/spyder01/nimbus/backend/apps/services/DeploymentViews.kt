package com.spyder01.nimbus.backend.apps.services

import com.spyder01.nimbus.backend.apps.dto.DeploymentDto
import com.spyder01.nimbus.backend.apps.dto.DeploymentTaskDto
import com.spyder01.nimbus.backend.apps.entities.Deployment
import com.spyder01.nimbus.backend.apps.entities.TaskState
import com.spyder01.nimbus.backend.apps.repositories.AppSpecRepository
import com.spyder01.nimbus.backend.apps.repositories.DeploymentTaskRepository
import org.springframework.stereotype.Component

@Component
class DeploymentViews(
    private val specs: AppSpecRepository,
    private val tasks: DeploymentTaskRepository,
) {
    /** Task lists are included for active deployments, or for all of them when [withTasks] is set. */
    fun toDtos(deployments: List<Deployment>, withTasks: Boolean = false): List<DeploymentDto> {
        if (deployments.isEmpty()) return emptyList()
        val ids = deployments.map { requireNotNull(it.id) }
        val revisions = specs.findAllById(deployments.map { it.specId }).associate { it.id to it.revision }

        val counts = tasks.countsByState(ids).groupBy { it.deploymentId }
        val detailed = deployments.filter { withTasks || it.state.active }.map { requireNotNull(it.id) }
        val taskRows = if (detailed.isEmpty()) emptyMap() else tasks.findAllByDeploymentIdInOrderByOrdinalAsc(detailed).groupBy { it.deploymentId }

        return deployments.map { d ->
            val id = requireNotNull(d.id)
            val byState = counts[id].orEmpty()
            val rows = taskRows[id]
            val names = rows?.associate { it.id to it.name }.orEmpty()
            DeploymentDto(
                id = id,
                appId = d.appId,
                versionRevision = revisions[d.specId],
                state = d.state,
                cancelRequested = d.cancelRequestedAt != null,
                error = d.error,
                requestedBy = d.requestedBy,
                createdAt = d.createdAt,
                startedAt = d.startedAt,
                finishedAt = d.finishedAt,
                tasksTotal = byState.sumOf { it.n }.toInt(),
                tasksSucceeded = byState.filter { it.state == TaskState.SUCCEEDED }.sumOf { it.n }.toInt(),
                tasks = rows?.map { t ->
                    DeploymentTaskDto(
                        id = requireNotNull(t.id), nodeId = t.nodeId, name = t.name, ordinal = t.ordinal, layer = t.layer,
                        state = t.state, attempts = t.attempts, error = t.error,
                        dependsOn = t.dependsOn.mapNotNull { names[it] },
                        startedAt = t.startedAt, finishedAt = t.finishedAt,
                    )
                },
            )
        }
    }

    fun toDto(d: Deployment, withTasks: Boolean = false): DeploymentDto = toDtos(listOf(d), withTasks).single()
}
