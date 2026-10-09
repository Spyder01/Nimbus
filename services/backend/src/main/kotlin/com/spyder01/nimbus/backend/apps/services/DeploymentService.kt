package com.spyder01.nimbus.backend.apps.services

import com.spyder01.nimbus.backend.apps.dto.DeploymentDto
import com.spyder01.nimbus.backend.apps.dto.DeploymentList
import com.spyder01.nimbus.backend.apps.entities.Deployment
import com.spyder01.nimbus.backend.apps.entities.DeploymentTask
import com.spyder01.nimbus.backend.apps.entities.DeploymentState
import com.spyder01.nimbus.backend.apps.entities.SpecKind
import com.spyder01.nimbus.backend.apps.entities.TaskState
import com.spyder01.nimbus.backend.apps.repositories.AppRepository
import com.spyder01.nimbus.backend.apps.repositories.AppSpecRepository
import com.spyder01.nimbus.backend.apps.repositories.DeploymentRepository
import com.spyder01.nimbus.backend.apps.repositories.DeploymentTaskRepository
import com.spyder01.nimbus.backend.shared.ApiException
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** What users do with deployments: request one, look at them, cancel one. Workers use [DeploymentWorkerService]. */
@Service
class DeploymentService(
    private val apps: AppRepository,
    private val specs: AppSpecRepository,
    private val deployments: DeploymentRepository,
    private val taskRepo: DeploymentTaskRepository,
    private val appService: AppService,
    private val validator: GraphValidator,
    private val lifecycle: DeploymentLifecycle,
    private val views: DeploymentViews,
    private val clock: Clock,
) {
    companion object {
        const val HISTORY_LIMIT = 50
    }

    /**
     * Validates the graph (including that it has no circular dependencies), pins it as a saved version
     * (reusing the latest one if the draft is unchanged), and queues a deployment of that version with
     * one task per container, in dependency order.
     */
    @Transactional
    fun deploy(owner: UUID, appId: UUID): DeploymentDto {
        val app = apps.findForUpdate(appId, owner) ?: throw appNotFound()

        if (deployments.existsByAppIdAndStateIn(appId, DeploymentState.ACTIVE)) {
            throw ApiException(HttpStatus.CONFLICT, "deployment_active", "A deployment is already queued or running")
        }

        val draft = specs.findByAppIdAndKind(appId, SpecKind.DRAFT) ?: error("App $appId has no draft")
        if (draft.nodes.isEmpty()) {
            throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "nothing_to_deploy", "Add at least one container before deploying")
        }
        val problems = validator.problems(draft.nodes, draft.edges)
        if (problems.isNotEmpty()) {
            throw ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "graph_incomplete",
                "Fix ${problems.size} ${if (problems.size == 1) "problem" else "problems"} before deploying",
                mapOf("problems" to problems),
            )
        }

        val version = appService.saveVersion(owner, appId, "Deployed").version
        val spec = specs.findByAppIdAndKindAndRevision(appId, SpecKind.SAVED, version.revision)
            ?: error("Saved version ${version.revision} of app $appId vanished")

        val deployment = deployments.save(Deployment(appId = appId, specId = requireNotNull(spec.id), requestedBy = owner))
        planTasks(deployment, spec.nodes, spec.edges)
        lifecycle.syncApp(app, deployment)
        return views.toDto(deployment)
    }

    /**
     * One task per container. The steps come in dependency order, so each task's dependencies already exist (and
     * have ids) when it is created. Tasks with nothing to wait for are ready at once; the rest start PENDING.
     */
    private fun planTasks(deployment: Deployment, nodes: List<com.spyder01.nimbus.backend.apps.dto.GraphNode>, edges: List<com.spyder01.nimbus.backend.apps.dto.GraphEdge>) {
        val steps = GraphAlgorithms.plan(nodes, edges)
        val idOfNode = HashMap<String, UUID>()
        for (step in steps) {
            val task = taskRepo.save(
                DeploymentTask(
                    deploymentId = requireNotNull(deployment.id),
                    nodeId = step.node.id,
                    name = step.node.data.name.trim(),
                    layer = step.layer,
                    ordinal = step.ordinal,
                    dependsOn = step.dependsOn.map { idOfNode.getValue(it) },
                    spec = step.node.data,
                    state = if (step.dependsOn.isEmpty()) TaskState.QUEUED else TaskState.PENDING,
                ),
            )
            idOfNode[step.node.id] = requireNotNull(task.id)
        }
    }

    @Transactional(readOnly = true)
    fun list(owner: UUID, appId: UUID): DeploymentList {
        val app = apps.findByIdAndOwnerId(appId, owner) ?: throw appNotFound()
        val rows = deployments.findAllByAppIdOrderByCreatedAtDesc(appId, PageRequest.of(0, HISTORY_LIMIT))
        return DeploymentList(app.state, views.toDtos(rows), views.publicUrls(listOf(app))[appId].orEmpty())
    }

    @Transactional(readOnly = true)
    fun get(owner: UUID, appId: UUID, deploymentId: UUID): DeploymentDto {
        apps.findByIdAndOwnerId(appId, owner) ?: throw appNotFound()
        val d = deployments.findById(deploymentId).orElse(null)?.takeIf { it.appId == appId } ?: throw deploymentNotFound()
        return views.toDto(d, withTasks = true)
    }

    /**
     * Marks the deployment as cancelled. Tasks that haven't started are cancelled at once; running tasks are told
     * to stop at their next heartbeat. The deployment becomes CANCELLED as soon as nothing is running, which is
     * immediately if no task has started. Finished deployments can't be cancelled.
     */
    @Transactional
    fun cancel(owner: UUID, appId: UUID, deploymentId: UUID): DeploymentDto {
        if (!apps.existsByIdAndOwnerId(appId, owner)) throw appNotFound()
        val locked = lifecycle.lock(deploymentId)?.takeIf { it.deployment.appId == appId } ?: throw deploymentNotFound()
        val d = locked.deployment
        if (!d.state.active) {
            throw ApiException(HttpStatus.CONFLICT, "not_cancellable", "This deployment has already finished")
        }
        if (d.cancelRequestedAt == null) {
            d.cancelRequestedAt = clock.instant()
            d.cancelledBy = owner
        }
        lifecycle.settle(locked)
        return views.toDto(d)
    }

    private fun appNotFound() = ApiException(HttpStatus.NOT_FOUND, "app_not_found", "App not found")

    private fun deploymentNotFound() = ApiException(HttpStatus.NOT_FOUND, "deployment_not_found", "Deployment not found")
}

