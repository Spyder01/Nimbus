package com.spyder01.nimbus.backend.users.repositories

import com.spyder01.nimbus.backend.users.entities.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID>
