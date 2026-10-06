package com.spyder01.nimbus.backend.apps.repositories

import com.spyder01.nimbus.backend.apps.entities.App
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface AppRepository : JpaRepository<App, UUID> {
    fun findByIdAndOwnerId(id: UUID, ownerId: UUID): App?

    /** Row-locks the app; used where version numbers are handed out so concurrent saves serialise. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from App a where a.id = :id and a.ownerId = :ownerId")
    fun findForUpdate(id: UUID, ownerId: UUID): App?

    /** Same lock without an owner check, for workers acting on a deployment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from App a where a.id = :id")
    fun lockById(id: UUID): App?

    fun findAllByOwnerIdOrderByUpdatedAtDesc(ownerId: UUID): List<App>

    /** Scalar ownership check: doesn't load (and so can't cache a stale copy of) the app row. */
    fun existsByIdAndOwnerId(id: UUID, ownerId: UUID): Boolean

    fun countByOwnerId(ownerId: UUID): Long

    fun existsByOwnerIdAndNameIgnoreCase(ownerId: UUID, name: String): Boolean

    fun existsByOwnerIdAndNameIgnoreCaseAndIdNot(ownerId: UUID, name: String, id: UUID): Boolean
}
