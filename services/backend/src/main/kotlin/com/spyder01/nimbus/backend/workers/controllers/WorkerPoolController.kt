package com.spyder01.nimbus.backend.workers.controllers

import com.spyder01.nimbus.backend.shared.userId
import com.spyder01.nimbus.backend.workers.dto.PoolDto
import com.spyder01.nimbus.backend.workers.services.WorkerService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/**
 * Worker pools and their default settings. Under its own path rather than /api/workers/pools, because a worker can
 * be named anything, including "pools". Admins only, like the workers themselves.
 */
@RestController
@RequestMapping("/api/worker-pools")
@PreAuthorize("hasRole('ADMIN')")
class WorkerPoolController(
    private val service: WorkerService,
) {
    @GetMapping
    fun list(): List<PoolDto> = service.pools()

    @GetMapping("/{name}")
    fun get(@PathVariable name: String): PoolDto = service.pool(name)

    /**
     * Replaces the pool's default settings. Send both fields; a null goes back to each worker's own startup value:
     * `{"parallelJobs": 8, "leaseSeconds": null}`. Workers with a value of their own keep it.
     */
    @PutMapping("/{name}/settings")
    fun replaceDefaults(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable name: String,
        @RequestBody body: JsonNode,
    ): PoolDto {
        val (parallelJobs, leaseSeconds) = parseSettingsBody(body)
        return service.replacePoolDefaults(name, parallelJobs, leaseSeconds, user.userId())
    }
}
