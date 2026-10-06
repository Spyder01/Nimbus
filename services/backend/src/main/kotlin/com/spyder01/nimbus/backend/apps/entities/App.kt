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
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

enum class AppState { DRAFT, DEPLOYING, RUNNING, DEGRADED, FAILED, STOPPED, DELETING }

// Only changed columns are written: deployment workers update `state` while autosave updates other columns.
@DynamicUpdate
@Entity
@Table(name = "apps")
class App(
    @Column(name = "owner_id")
    var ownerId: UUID,
    var name: String,
    @Enumerated(EnumType.STRING)
    var state: AppState = AppState.DRAFT,
    @Column(name = "component_count")
    var componentCount: Int = 0,
    @Column(name = "version_seq")
    var versionSeq: Int = 0,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
}
