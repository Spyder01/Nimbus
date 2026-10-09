package com.spyder01.nimbus.backend.users.dto

import com.spyder01.nimbus.backend.users.entities.Role
import java.time.Instant
import java.util.UUID

/** A person with an account, as admins see them. */
data class MemberDto(
    val id: UUID,
    val name: String?,
    val email: String?,
    val avatarUrl: String?,
    val role: Role,
    /** How they sign in (their first sign-in method): "GITHUB" or "GOOGLE". */
    val provider: String?,
    /** Their handle with that provider, e.g. their GitHub username. */
    val login: String?,
    /** False until they have saved their profile once. */
    val profileUpdated: Boolean,
    val createdAt: Instant?,
)

data class MemberPageDto(
    val items: List<MemberDto>,
    /** How many members match, across all pages. */
    val total: Long,
    /** Zero-based. */
    val page: Int,
    val size: Int,
)
