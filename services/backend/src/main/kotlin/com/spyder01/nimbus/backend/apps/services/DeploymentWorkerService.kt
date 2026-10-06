package com.spyder01.nimbus.backend.apps.services

import com.spyder01.nimbus.backend.apps.entities.DeploymentState
import com.spyder01.nimbus.backend.apps.entities.DeploymentTask
import com.spyder01.nimbus.backend.apps.entities.TaskState
import com.spyder01.nimbus.backend.apps.repositories.DeploymentTaskRepository
import com.spyder01.nimbus.backend.configuration.DeploymentProperties
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The worker-facing side of deployments (the worker itself is added later; nothing calls this yet).
 * Workers deal in tasks: one per container, each with its own lease.
 *
 * Task rules:
 *  - Only QUEUED tasks are claimable. A task is QUEUED once everything it needs has SUCCEEDED, so claiming in any
 *    order always respects the dependency order, and independent tasks can run in parallel on different workers.
 *  - [claim] sets `lease_expires_at = now + lease-duration`. That expiry is fixed; [heartbeat] only refreshes
 *    `lease_updated_at`. Only the lease holder, before expiry, may [complete], [fail] or [acknowledgeStop].
 *  - [reclaimStale] picks up leased tasks whose lease expired or whose heartbeat went quiet: cancelled if the
 *    deployment is being stopped, else queued again, else FAILED once `max-attempts` claims were used.
 *
 * Deployment rules (applied by [DeploymentLifecycle.settle] after every change): the deployment becomes
 * IN_PROGRESS when its first task is claimed and SUCCEEDED when every task has succeeded. If a task fails for
 * good, tasks that haven't started are cancelled, running ones are told to stop (see [heartbeat]) and the
 * deployment ends FAILED once nothing is running.
 */
@Service
class DeploymentWorkerService(
    private val tasks: DeploymentTaskRepository,
    private val lifecycle: DeploymentLifecycle,
    private val props: DeploymentProperties,
    private val clock: Clock,
) {
    companion object {
        private const val CANDIDATES = 10
    }

    /** The oldest ready task this worker manages to claim, or null if there is nothing to do. */
    @Transactional
    fun claimNext(workerId: String): DeploymentTask? {
        for (id in tasks.idsByState(TaskState.QUEUED, PageRequest.of(0, CANDIDATES))) claim(id, workerId)?.let { return it }
        return null
    }

    /** Null if the task is no longer QUEUED (claimed by someone else, or cancelled in the meantime). */
    @Transactional
    fun claim(taskId: UUID, workerId: String): DeploymentTask? {
        val (l, t) = lifecycle.lockForTask(taskId) ?: return null
        if (t.state != TaskState.QUEUED || !l.deployment.state.active) return null

        val now = clock.instant()
        t.state = TaskState.IN_PROGRESS
        t.attempts += 1
        t.leaseOwner = workerId
        t.leaseAcquiredAt = now
        t.leaseUpdatedAt = now
        t.leaseExpiresAt = now.plus(props.leaseDuration)
        if (t.startedAt == null) t.startedAt = now

        if (l.deployment.state == DeploymentState.QUEUED) {
            l.deployment.startedAt = now
            lifecycle.transition(l, DeploymentState.IN_PROGRESS)
        }
        return t
    }

    data class Heartbeat(val holdsLease: Boolean, val stopRequested: Boolean)

    /** Says whether the worker still holds the lease, and whether it should stop (cancelled, or another task failed). */
    @Transactional
    fun heartbeat(taskId: UUID, workerId: String): Heartbeat {
        val (l, t) = lifecycle.lockForTask(taskId) ?: return Heartbeat(false, false)
        if (!holds(t, workerId)) return Heartbeat(false, false)
        t.leaseUpdatedAt = clock.instant()
        return Heartbeat(true, l.deployment.cancelRequestedAt != null || l.deployment.abortRequestedAt != null)
    }

    @Transactional
    fun complete(taskId: UUID, workerId: String): Boolean = finish(taskId, workerId, TaskState.SUCCEEDED, null, abort = false)

    /** The task failed for good; the rest of the deployment is abandoned. */
    @Transactional
    fun fail(taskId: UUID, workerId: String, error: String): Boolean = finish(taskId, workerId, TaskState.FAILED, error, abort = true)

    /** The worker stopped because a heartbeat said to. */
    @Transactional
    fun acknowledgeStop(taskId: UUID, workerId: String): Boolean = finish(taskId, workerId, TaskState.CANCELLED, "Stopped", abort = false)

    /** Returns how many abandoned tasks were handled. Meant to be called periodically. */
    @Transactional
    fun reclaimStale(): Int {
        val now = clock.instant()
        var handled = 0
        for (id in tasks.staleIds(TaskState.IN_PROGRESS, now, now.minus(props.heartbeatTimeout))) {
            val (l, t) = lifecycle.lockForTask(id) ?: continue
            if (t.state != TaskState.IN_PROGRESS || !isStale(t)) continue // changed while we were looking
            val d = l.deployment
            val stopping = d.cancelRequestedAt != null || d.abortRequestedAt != null

            when {
                stopping -> {
                    end(t, TaskState.CANCELLED, "Stopped")
                    lifecycle.settle(l)
                }
                t.attempts >= props.maxAttempts -> {
                    end(t, TaskState.FAILED, "The worker stopped responding (${t.attempts} of ${props.maxAttempts} attempts used)")
                    d.abortRequestedAt = now
                    lifecycle.settle(l)
                }
                else -> {
                    t.leaseOwner = null
                    t.leaseAcquiredAt = null
                    t.leaseExpiresAt = null
                    t.leaseUpdatedAt = null
                    t.state = TaskState.QUEUED
                    t.error = "The worker stopped responding; queued again"
                }
            }
            handled++
        }
        return handled
    }

    private fun finish(taskId: UUID, workerId: String, to: TaskState, error: String?, abort: Boolean): Boolean {
        val (l, t) = lifecycle.lockForTask(taskId) ?: return false
        if (!holds(t, workerId)) return false
        end(t, to, error)
        if (abort && l.deployment.abortRequestedAt == null) l.deployment.abortRequestedAt = clock.instant()
        lifecycle.settle(l)
        return true
    }

    private fun end(t: DeploymentTask, to: TaskState, error: String?) {
        t.state = to
        t.finishedAt = clock.instant()
        if (error != null) t.error = error
    }

    private fun holds(t: DeploymentTask, workerId: String): Boolean =
        t.state == TaskState.IN_PROGRESS && t.leaseOwner == workerId && clock.instant().isBefore(requireNotNull(t.leaseExpiresAt))

    private fun isStale(t: DeploymentTask): Boolean {
        val now = clock.instant()
        return !now.isBefore(requireNotNull(t.leaseExpiresAt)) || requireNotNull(t.leaseUpdatedAt).isBefore(now.minus(props.heartbeatTimeout))
    }
}
