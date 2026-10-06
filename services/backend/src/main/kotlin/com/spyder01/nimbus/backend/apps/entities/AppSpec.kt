package com.spyder01.nimbus.backend.apps.entities

import com.spyder01.nimbus.backend.apps.dto.GraphEdge
import com.spyder01.nimbus.backend.apps.dto.GraphNode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

enum class SpecKind { DRAFT, SAVED }

/**
 * One design of an app. A DRAFT is mutable (autosave); `revision` is its update counter.
 * A SAVED row is immutable; `revision` is its version number.
 */
@Entity
@Table(name = "app_specs")
class AppSpec(
    @Column(name = "app_id")
    var appId: UUID,
    @Enumerated(EnumType.STRING)
    var kind: SpecKind,
    var revision: Long,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var nodes: List<GraphNode> = emptyList(),
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    var edges: List<GraphEdge> = emptyList(),
    @Column(name = "content_hash")
    var contentHash: String,
    var note: String? = null,
    @Column(name = "created_by")
    var createdBy: UUID? = null,
    @Column(name = "schema_version")
    var schemaVersion: Int = 1,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    /** Guards against two concurrent writers of the same row (the 409 for races past the revision check). */
    @Version
    @Column(name = "lock_version")
    var lockVersion: Long = 0

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at")
    var updatedAt: Instant? = null
}
