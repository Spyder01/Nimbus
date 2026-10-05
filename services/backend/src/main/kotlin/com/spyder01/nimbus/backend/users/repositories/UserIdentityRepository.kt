package com.spyder01.nimbus.backend.users.repositories

import com.spyder01.nimbus.backend.users.entities.Provider
import com.spyder01.nimbus.backend.users.entities.UserIdentity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserIdentityRepository: JpaRepository<UserIdentity, UUID> {
    fun findByProviderAndProviderUserId(provider: Provider, providerUserId: String): UserIdentity?
    fun existsByProviderAndProviderUserId(provider: Provider, providerUserId: String): Boolean
    fun findAllByUserId(userId: UUID): List<UserIdentity>
}