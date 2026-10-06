package com.spyder01.nimbus.backend.apps.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

enum class DeploymentState {
    QUEUED,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ;

    /** Still waiting for, or being handled by, a worker. */
    val active: Boolean get() = this == QUEUED || this == IN_PROGRESS

    companion object {
        val ACTIVE: List<DeploymentState> = entries.filter { it.active }
    }
}

@Entity
@Table(name = "deployments")
class Deployment(
    @Column(name = "app_id")
    var appId: UUID,
    @Column(name = "spec_id")
    var specId: UUID,
    @Column(name = "requested_by")
    var requestedBy: UUID? = null,
    @Enumerated(EnumType.STRING)
    var state: DeploymentState = DeploymentState.QUEUED,
    @Column(name = "cancel_requested_at")
    var cancelRequestedAt: Instant? = null,
    @Column(name = "cancelled_by")
    var cancelledBy: UUID? = null,
    /** A task failed for good: remaining work is cancelled and running tasks are told to stop. */
    @Column(name = "abort_requested_at")
    var abortRequestedAt: Instant? = null,
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
