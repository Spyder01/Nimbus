package com.spyder01.nimbus.backend.apps.entities

import com.spyder01.nimbus.backend.apps.dto.ContainerData
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class TaskState {
    /** Waiting for the containers it needs. */
    PENDING,

    /** Ready: nothing it needs is outstanding, so a worker may claim it. */
    QUEUED,

    /** Leased by a worker. */
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ;

    /** Not finished yet. */
    val active: Boolean get() = this == PENDING || this == QUEUED || this == IN_PROGRESS
}

/** Deploys one container of one deployment. Has its own lease; the deployment's state follows its tasks. */
@Entity
@Table(name = "deployment_tasks")
class DeploymentTask(
    @Column(name = "deployment_id")
    var deploymentId: UUID,
    @Column(name = "node_id")
    var nodeId: String,
    var name: String,
    var layer: Int,
    var ordinal: Int,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "depends_on", columnDefinition = "uuid[]")
    var dependsOn: List<UUID> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var spec: ContainerData,
    @Enumerated(EnumType.STRING)
    var state: TaskState,
    var attempts: Int = 0,
    @Column(name = "lease_owner")
    var leaseOwner: String? = null,
    @Column(name = "lease_acquired_at")
    var leaseAcquiredAt: Instant? = null,
    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null,
    @Column(name = "lease_updated_at")
    var leaseUpdatedAt: Instant? = null,
    var error: String? = null,
    @Column(name = "started_at")
    var startedAt: Instant? = null,
    @Column(name = "finished_at")
    var finishedAt: Instant? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null
}
