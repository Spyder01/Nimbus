package com.spyder01.nimbus.backend.apps.services

import com.spyder01.nimbus.backend.apps.entities.App
import com.spyder01.nimbus.backend.apps.entities.AppState
import com.spyder01.nimbus.backend.apps.entities.Deployment
import com.spyder01.nimbus.backend.apps.entities.DeploymentState
import com.spyder01.nimbus.backend.apps.entities.DeploymentTask
import com.spyder01.nimbus.backend.apps.entities.TaskState
import com.spyder01.nimbus.backend.apps.repositories.AppRepository
import com.spyder01.nimbus.backend.apps.repositories.DeploymentRepository
import com.spyder01.nimbus.backend.apps.repositories.DeploymentTaskRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Shared rules for moving a deployment and its tasks between states, used by user actions and workers.
 *
 * Everything that changes a deployment or its tasks first locks the app row ([lock] / [lockForTask]), so
 * all such changes for one app are serialised and a cancel racing a worker can't deadlock.
 *
 * The deployment's state is never set directly from outside: after any task or cancel change, [settle]
 * works out what the deployment should now be.
 */
@Component
class DeploymentLifecycle(
    private val apps: AppRepository,
    private val deployments: DeploymentRepository,
    private val tasks: DeploymentTaskRepository,
    private val clock: Clock,
) {
    class Locked(val app: App, val deployment: Deployment)

    fun lock(deploymentId: UUID): Locked? {
        val appId = deployments.appIdOf(deploymentId) ?: return null
        val app = apps.lockById(appId) ?: return null
        val deployment = deployments.findForUpdate(deploymentId) ?: return null
        return Locked(app, deployment)
    }

    fun lockForTask(taskId: UUID): Pair<Locked, DeploymentTask>? {
        val deploymentId = tasks.deploymentIdOf(taskId) ?: return null
        val locked = lock(deploymentId) ?: return null
        val task = tasks.findById(taskId).orElse(null) ?: return null
        return locked to task
    }

    /** Moves the deployment to [to], stamping the finish time for terminal states, and keeps the app's state in step. */
    fun transition(l: Locked, to: DeploymentState, error: String? = null) {
        val d = l.deployment
        d.state = to
        if (error != null) d.error = error
        if (!to.active) d.finishedAt = clock.instant()
        syncApp(l.app, d)
    }

    /**
     * Brings the deployment's tasks and state up to date after something changed:
     *  - if it is being stopped (user cancel, or a task failed), tasks that haven't started are cancelled;
     *    otherwise tasks whose dependencies have all succeeded become ready;
     *  - once nothing is running or waiting, the deployment itself finishes.
     */
    fun settle(l: Locked) {
        val d = l.deployment
        if (!d.state.active) return
        val all = tasks.findAllByDeploymentIdOrderByOrdinalAsc(requireNotNull(d.id))
        val now = clock.instant()
        val stopping = d.cancelRequestedAt != null || d.abortRequestedAt != null

        if (stopping) {
            for (t in all.filter { it.state == TaskState.PENDING || it.state == TaskState.QUEUED }) {
                t.state = TaskState.CANCELLED
                t.finishedAt = now
                if (t.error == null) t.error = "Skipped: the deployment was stopped"
            }
        } else {
            val done = all.filter { it.state == TaskState.SUCCEEDED }.mapTo(HashSet()) { it.id }
            for (t in all.filter { it.state == TaskState.PENDING && done.containsAll(it.dependsOn) }) t.state = TaskState.QUEUED
        }

        if (all.any { it.state.active }) return // running tasks must stop on their own, or work is still waiting

        val failed = all.firstOrNull { it.state == TaskState.FAILED }
        when {
            d.cancelRequestedAt != null ->
                transition(l, DeploymentState.CANCELLED, if (d.startedAt == null) "Cancelled before it started" else "Cancelled")
            failed != null ->
                transition(l, DeploymentState.FAILED, "Container ${failed.name} failed" + (failed.error?.let { ": $it" } ?: ""))
            all.all { it.state == TaskState.SUCCEEDED } -> transition(l, DeploymentState.SUCCEEDED)
            else -> transition(l, DeploymentState.FAILED, "The deployment ended before every container was deployed")
        }
    }

    fun syncApp(app: App, d: Deployment) {
        app.state =
            when (d.state) {
                DeploymentState.QUEUED, DeploymentState.IN_PROGRESS -> AppState.DEPLOYING
                DeploymentState.SUCCEEDED -> AppState.RUNNING
                DeploymentState.FAILED -> AppState.FAILED
                // Cancelled: fall back to what was running before, or a plain draft if nothing ever succeeded.
                DeploymentState.CANCELLED ->
                    if (deployments.existsByAppIdAndState(requireNotNull(app.id), DeploymentState.SUCCEEDED)) AppState.RUNNING
                    else AppState.DRAFT
            }
    }
}
