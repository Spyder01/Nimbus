package com.spyder01.nimbus.backend.users.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "user_identities",
    uniqueConstraints = [UniqueConstraint(columnNames = ["provider", "provider_user_id"])],
)
class UserIdentity(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    var user: User,
    @Enumerated(EnumType.STRING)
    var provider: Provider,
    @Column(name = "provider_user_id")
    var providerUserId: String,
    var login: String? = null,
    var email: String? = null,
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
