package com.spyder01.nimbus.backend.workers.dto

import java.time.Instant
import java.util.UUID

enum class WorkerStatus {
    /** Its slot lease is current: it has sent a heartbeat recently. */
    ONLINE,

    /** The lease has run out (the worker stopped or was cut off). The slot is free for another worker to take over. */
    OFFLINE,
}

/** The two settings a worker can have changed while it runs. A null means "not set". */
data class SettingValues(
    val parallelJobs: Int? = null,
    val leaseSeconds: Int? = null,
)

/** How a worker's settings come about, and whether the worker has caught up with them. */
data class WorkerSettingsView(
    /** Set on this worker alone. A null value inherits from the pool. */
    val override: SettingValues,
    /** The pool's default, which applies to workers without their own value. */
    val poolDefault: SettingValues,
    /** What applies: the override, else the pool default. A null means the worker's own startup value applies. */
    val effective: SettingValues,
    /** The highest version of the stored settings that apply to this worker (0 if none are stored). */
    val desiredVersion: Long,
    /** The version the worker last reported applying; null if it hasn't reported. */
    val appliedVersion: Long?,
    /** True once the worker has applied the current stored settings. False while a change is still pending. */
    val inSync: Boolean,
    /** Why the worker refused the stored settings, if it did. */
    val problem: String?,
    val overrideUpdatedAt: Instant?,
    val overrideUpdatedBy: UUID?,
)

data class WorkerDto(
    /** The worker's stable name, e.g. "default-0". */
    val name: String,
    val pool: String,
    /** Position in the pool; null for a worker that asked for its name. */
    val ordinal: Int?,
    /** The process currently holding the name. New on every restart. */
    val instanceId: String,
    val version: String?,
    val host: String?,
    val status: WorkerStatus,
    val startedAt: Instant,
    val lastSeenAt: Instant,
    val leaseExpiresAt: Instant,
    /** Jobs it is running right now. */
    val heldJobs: Int,
    val settings: WorkerSettingsView,
)

data class WorkerSettingsDto(
    val name: String,
    val pool: String,
    val settings: WorkerSettingsView,
)

/** A group of workers that share default settings. */
data class PoolDto(
    val name: String,
    val workers: Int,
    val online: Int,
    /** How many of its workers have a value of their own, and so ignore the default for that setting. */
    val overriding: Int,
    /** What workers without their own value use. A null means each worker's own startup value applies. */
    val defaults: SettingValues,
    val updatedAt: Instant?,
    val updatedBy: UUID?,
)
