package com.spyder01.nimbus.backend.users.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    var name: String? = null,
    var email: String? = null,
    @Column(name = "avatar_url")
    var avatarUrl: String? = null,
    @Enumerated(EnumType.STRING)
    var role: Role = Role.USER,
    @Column(name = "profile_updated")
    var profileUpdated: Boolean = false,
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
