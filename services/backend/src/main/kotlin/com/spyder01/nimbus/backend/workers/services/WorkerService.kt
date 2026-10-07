package com.spyder01.nimbus.backend.workers.services

import com.spyder01.nimbus.backend.shared.ApiException
import com.spyder01.nimbus.backend.workers.dto.WorkerDto
import com.spyder01.nimbus.backend.workers.dto.WorkerSettingsDto
import com.spyder01.nimbus.backend.workers.repositories.WorkerRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WorkerService(
    private val workers: WorkerRepository,
) {
    companion object {
        // The same limits as the worker's own config and the CHECK constraints in the migration; change them together.
        const val MIN_PARALLEL_JOBS = 1
        const val MAX_PARALLEL_JOBS = 100
        const val MIN_LEASE_SECONDS = 60
        const val MAX_LEASE_SECONDS = 24 * 60 * 60
    }

    @Transactional(readOnly = true)
    fun list(pool: String?): List<WorkerDto> = workers.find(pool = pool?.takeIf { it.isNotBlank() })

    @Transactional(readOnly = true)
    fun get(name: String): WorkerDto = workers.find(name = name).singleOrNull() ?: throw notFound(name)

    @Transactional(readOnly = true)
    fun settings(name: String): WorkerSettingsDto = get(name).let { WorkerSettingsDto(it.name, it.pool, it.settings) }

    /**
     * Replaces the worker's own settings: a null value goes back to inheriting the pool's. The worker picks the
     * change up on its next heartbeat (within about 10 seconds); until then `inSync` is false.
     */
    @Transactional
    fun replaceSettings(name: String, parallelJobs: Int?, leaseSeconds: Int?, updatedBy: UUID): WorkerSettingsDto {
        if (!workers.exists(name)) throw notFound(name)

        val errors = mutableListOf<Map<String, String>>()
        if (parallelJobs != null && parallelJobs !in MIN_PARALLEL_JOBS..MAX_PARALLEL_JOBS) {
            errors += mapOf("field" to "parallelJobs", "message" to "Must be between $MIN_PARALLEL_JOBS and $MAX_PARALLEL_JOBS")
        }
        if (leaseSeconds != null && leaseSeconds !in MIN_LEASE_SECONDS..MAX_LEASE_SECONDS) {
            errors += mapOf("field" to "leaseSeconds", "message" to "Must be between $MIN_LEASE_SECONDS and $MAX_LEASE_SECONDS")
        }
        if (errors.isNotEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "invalid_settings", "The settings are not valid", mapOf("errors" to errors))
        }

        workers.replaceOverride(name, parallelJobs, leaseSeconds, updatedBy)
        return settings(name)
    }

    private fun notFound(name: String) = ApiException(HttpStatus.NOT_FOUND, "worker_not_found", "No worker is registered as '$name'")
}
