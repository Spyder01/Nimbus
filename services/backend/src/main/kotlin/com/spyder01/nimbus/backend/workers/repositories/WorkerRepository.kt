package com.spyder01.nimbus.backend.workers.repositories

import com.spyder01.nimbus.backend.workers.dto.SettingValues
import com.spyder01.nimbus.backend.workers.dto.WorkerDto
import com.spyder01.nimbus.backend.workers.dto.WorkerSettingsView
import com.spyder01.nimbus.backend.workers.dto.WorkerStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Reads and writes the worker tables. The worker itself (a separate Go process) writes worker_slots directly; the
 * backend only reads it, and writes worker_settings. Plain SQL, since the list is a join with a count.
 */
@Repository
class WorkerRepository(
    private val jdbc: JdbcClient,
) {
    /** Workers, optionally of one pool and/or by exact name, ordered by pool then position. */
    fun find(pool: String? = null, name: String? = null): List<WorkerDto> =
        jdbc.sql(
            """
            SELECT s.name, s.pool, s.ordinal, s.instance_id, s.version, s.host,
                   s.started_at, s.last_seen_at, s.lease_expires_at,
                   s.lease_expires_at > clock_timestamp() AS online,
                   s.applied_settings_version, s.settings_error,
                   (SELECT count(*) FROM deployment_tasks t
                     WHERE t.lease_owner = s.instance_id AND t.state = 'IN_PROGRESS') AS held_jobs,
                   w.parallel_jobs AS w_jobs, w.lease_seconds AS w_lease, w.version AS w_version, w.updated_at AS w_updated, w.updated_by AS w_by,
                   p.parallel_jobs AS p_jobs, p.lease_seconds AS p_lease, p.version AS p_version
            FROM worker_slots s
            LEFT JOIN worker_settings w ON w.scope = 'WORKER' AND w.name = s.name
            LEFT JOIN worker_settings p ON p.scope = 'POOL' AND p.name = s.pool
            WHERE (CAST(:pool AS TEXT) IS NULL OR s.pool = CAST(:pool AS TEXT))
              AND (CAST(:name AS TEXT) IS NULL OR s.name = CAST(:name AS TEXT))
            ORDER BY s.pool, s.ordinal NULLS LAST, s.name
            """.trimIndent(),
        )
            .param("pool", pool)
            .param("name", name)
            .query { rs, _ -> rs.toWorker() }
            .list()

    fun exists(name: String): Boolean =
        jdbc.sql("SELECT EXISTS (SELECT 1 FROM worker_slots WHERE name = :name)")
            .param("name", name)
            .query(Boolean::class.java)
            .single()

    /**
     * Replaces this worker's own settings. A null value inherits from the pool. The database gives the row a new
     * version and update time (a trigger), which is how the worker notices the change.
     */
    fun replaceOverride(name: String, parallelJobs: Int?, leaseSeconds: Int?, updatedBy: UUID) {
        jdbc.sql(
            """
            INSERT INTO worker_settings (scope, name, parallel_jobs, lease_seconds, updated_by)
            VALUES ('WORKER', :name, :jobs, :lease, :by)
            ON CONFLICT (scope, name) DO UPDATE
                SET parallel_jobs = EXCLUDED.parallel_jobs,
                    lease_seconds = EXCLUDED.lease_seconds,
                    updated_by    = EXCLUDED.updated_by
            """.trimIndent(),
        )
            .param("name", name)
            .param("jobs", parallelJobs, java.sql.Types.INTEGER)
            .param("lease", leaseSeconds, java.sql.Types.INTEGER)
            .param("by", updatedBy)
            .update()
    }

    private fun ResultSet.toWorker(): WorkerDto {
        fun int(col: String): Int? = getInt(col).takeUnless { wasNull() }
        fun long(col: String): Long? = getLong(col).takeUnless { wasNull() }
        fun time(col: String) = getObject(col, OffsetDateTime::class.java)

        val own = SettingValues(int("w_jobs"), int("w_lease"))
        val pool = SettingValues(int("p_jobs"), int("p_lease"))
        val desired = maxOf(long("w_version") ?: 0, long("p_version") ?: 0)
        val applied = long("applied_settings_version")
        val problem = getString("settings_error")

        return WorkerDto(
            name = getString("name"),
            pool = getString("pool"),
            ordinal = int("ordinal"),
            instanceId = getString("instance_id"),
            version = getString("version"),
            host = getString("host"),
            status = if (getBoolean("online")) WorkerStatus.ONLINE else WorkerStatus.OFFLINE,
            startedAt = time("started_at").toInstant(),
            lastSeenAt = time("last_seen_at").toInstant(),
            leaseExpiresAt = time("lease_expires_at").toInstant(),
            heldJobs = getInt("held_jobs"),
            settings = WorkerSettingsView(
                override = own,
                poolDefault = pool,
                effective = SettingValues(own.parallelJobs ?: pool.parallelJobs, own.leaseSeconds ?: pool.leaseSeconds),
                desiredVersion = desired,
                appliedVersion = applied,
                inSync = applied != null && applied >= desired && problem == null,
                problem = problem,
                overrideUpdatedAt = time("w_updated")?.toInstant(),
                overrideUpdatedBy = getObject("w_by", UUID::class.java),
            ),
        )
    }
}
