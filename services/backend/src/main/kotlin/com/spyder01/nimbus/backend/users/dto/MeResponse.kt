package com.spyder01.nimbus.backend.users.dto

import com.spyder01.nimbus.backend.users.entities.Role
import com.spyder01.nimbus.backend.users.entities.User
import java.util.UUID

data class MeResponse(
    val id: UUID,
    val name: String?,
    val email: String?,
    val avatarUrl: String?,
    val profileUpdated: Boolean,
    val role: Role,
) {
    companion object {
        fun from(user: User) =
            MeResponse(requireNotNull(user.id), user.name, user.email, user.avatarUrl, user.profileUpdated, user.role)
    }
}
