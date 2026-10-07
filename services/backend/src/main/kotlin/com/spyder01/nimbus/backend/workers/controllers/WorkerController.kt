package com.spyder01.nimbus.backend.workers.controllers

import com.spyder01.nimbus.backend.shared.ApiException
import com.spyder01.nimbus.backend.shared.userId
import com.spyder01.nimbus.backend.workers.dto.WorkerDto
import com.spyder01.nimbus.backend.workers.dto.WorkerSettingsDto
import com.spyder01.nimbus.backend.workers.services.WorkerService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/** Workers are infrastructure, not user data: everything here is for admins only. */
@RestController
@RequestMapping("/api/workers")
@PreAuthorize("hasRole('ADMIN')")
class WorkerController(
    private val service: WorkerService,
) {
    /** The registered workers (online and offline), optionally of one pool. */
    @GetMapping
    fun list(@RequestParam(required = false) pool: String?): List<WorkerDto> = service.list(pool)

    @GetMapping("/{name}")
    fun get(@PathVariable name: String): WorkerDto = service.get(name)

    /** The worker's own settings, its pool's default, and what applies, with whether the worker has caught up. */
    @GetMapping("/{name}/settings")
    fun settings(@PathVariable name: String): WorkerSettingsDto = service.settings(name)

    /**
     * Replaces the worker's own settings. Send both fields; a null inherits from the pool:
     * `{"parallelJobs": 8, "leaseSeconds": null}`. Both must be present, and nothing else may be, so a misspelt field
     * can't silently clear an override.
     */
    @PutMapping("/{name}/settings")
    fun replaceSettings(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable name: String,
        @RequestBody body: JsonNode,
    ): WorkerSettingsDto =
        service.replaceSettings(name, whole(body, "parallelJobs"), whole(body, "leaseSeconds"), user.userId())

    private fun whole(body: JsonNode, field: String): Int? {
        val allowed = setOf("parallelJobs", "leaseSeconds")
        if (!body.isObject || body.propertyNames().any { it !in allowed }) throw invalid(allowed)
        val value = body.get(field) ?: throw invalid(allowed)
        return when {
            value.isNull -> null
            value.isIntegralNumber && value.canConvertToInt() -> value.intValue()
            else -> throw ApiException(HttpStatus.BAD_REQUEST, "invalid_body", "$field must be a whole number or null")
        }
    }

    private fun invalid(allowed: Set<String>) = ApiException(
        HttpStatus.BAD_REQUEST, "invalid_body",
        "The body must be a JSON object with exactly ${allowed.joinToString(" and ") { "'$it'" }} (each a whole number, or null to inherit)",
    )
}
