package com.spyder01.nimbus.backend.apps.repositories

import com.spyder01.nimbus.backend.apps.entities.Deployment
import com.spyder01.nimbus.backend.apps.entities.DeploymentState
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface DeploymentRepository : JpaRepository<Deployment, UUID> {
    fun findAllByAppIdOrderByCreatedAtDesc(appId: UUID, page: Pageable): List<Deployment>

    fun findFirstByAppIdOrderByCreatedAtDesc(appId: UUID): Deployment?

    fun findFirstByAppIdAndStateInOrderByCreatedAtDesc(appId: UUID, states: Collection<DeploymentState>): Deployment?

    fun findAllByAppIdInAndStateIn(appIds: Collection<UUID>, states: Collection<DeploymentState>): List<Deployment>

    fun existsByAppIdAndStateIn(appId: UUID, states: Collection<DeploymentState>): Boolean

    fun existsByAppIdAndState(appId: UUID, state: DeploymentState): Boolean

    /** Scalar on purpose: loading the entity here would cache it before the row lock is taken. */
    @Query("select d.appId from Deployment d where d.id = :id")
    fun appIdOf(id: UUID): UUID?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Deployment d where d.id = :id")
    fun findForUpdate(id: UUID): Deployment?

    /** Saved versions that deployments point at; these must survive version pruning. */
    @Query("select d.specId from Deployment d where d.appId = :appId")
    fun specIdsOf(appId: UUID): List<UUID>
}
