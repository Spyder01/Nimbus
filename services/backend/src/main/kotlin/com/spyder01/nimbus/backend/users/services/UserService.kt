package com.spyder01.nimbus.backend.users.services

import com.spyder01.nimbus.backend.users.dto.UpdateProfileRequest
import com.spyder01.nimbus.backend.users.entities.User
import com.spyder01.nimbus.backend.users.repositories.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val users: UserRepository,
) {
    @Transactional
    fun updateProfile(userId: UUID, request: UpdateProfileRequest): User? =
        users.findById(userId).orElse(null)?.also {
            it.name = request.name.trim()
            it.email = request.email?.trim()?.ifBlank { null }
            it.avatarUrl = request.avatarUrl?.trim()?.ifBlank { null }
            it.profileUpdated = true
        }
}
