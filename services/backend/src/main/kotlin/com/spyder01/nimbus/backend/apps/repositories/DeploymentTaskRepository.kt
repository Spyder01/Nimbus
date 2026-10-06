package com.spyder01.nimbus.backend.apps.repositories

import com.spyder01.nimbus.backend.apps.entities.DeploymentTask
import com.spyder01.nimbus.backend.apps.entities.TaskState
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface TaskCount {
    val deploymentId: UUID
    val state: TaskState
    val n: Long
}

interface DeploymentTaskRepository : JpaRepository<DeploymentTask, UUID> {
    fun findAllByDeploymentIdOrderByOrdinalAsc(deploymentId: UUID): List<DeploymentTask>

    fun findAllByDeploymentIdInOrderByOrdinalAsc(deploymentIds: Collection<UUID>): List<DeploymentTask>

    /** Scalar on purpose: loading the entity here would cache it before the locks are taken. */
    @Query("select t.deploymentId from DeploymentTask t where t.id = :id")
    fun deploymentIdOf(id: UUID): UUID?

    @Query(
        "select t.deploymentId as deploymentId, t.state as state, count(t) as n from DeploymentTask t " +
            "where t.deploymentId in :ids group by t.deploymentId, t.state",
    )
    fun countsByState(ids: Collection<UUID>): List<TaskCount>

    /** Oldest ready task first. Not locked: claiming re-checks under the app lock. */
    @Query("select t.id from DeploymentTask t where t.state = :state order by t.createdAt asc, t.ordinal asc")
    fun idsByState(state: TaskState, page: Pageable): List<UUID>

    /** Candidates only; staleness is re-checked once the app is locked. */
    @Query("select t.id from DeploymentTask t where t.state = :state and (t.leaseExpiresAt <= :now or t.leaseUpdatedAt < :staleBefore)")
    fun staleIds(state: TaskState, now: Instant, staleBefore: Instant): List<UUID>
}
