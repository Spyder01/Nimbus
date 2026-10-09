package com.spyder01.nimbus.backend.users.repositories

import com.spyder01.nimbus.backend.users.entities.Role
import com.spyder01.nimbus.backend.users.entities.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    /** Just the role, for checking permissions on every request. Null if there is no such user. */
    @Query("select u.role from User u where u.id = :id")
    fun roleOf(id: UUID): Role?
}
