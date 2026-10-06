package com.spyder01.nimbus.backend.apps.repositories

import com.spyder01.nimbus.backend.apps.entities.AppSpec
import com.spyder01.nimbus.backend.apps.entities.SpecKind
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AppSpecRepository : JpaRepository<AppSpec, UUID> {
    fun findByAppIdAndKind(appId: UUID, kind: SpecKind): AppSpec?

    fun findByAppIdAndKindAndRevision(appId: UUID, kind: SpecKind, revision: Long): AppSpec?

    fun findFirstByAppIdAndKindOrderByRevisionDesc(appId: UUID, kind: SpecKind): AppSpec?

    fun findAllByAppIdAndKindOrderByRevisionDesc(appId: UUID, kind: SpecKind): List<AppSpec>
}
