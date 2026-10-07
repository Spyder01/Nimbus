package com.spyder01.nimbus.backend.apps.services

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Takes back tasks whose worker went quiet: [DeploymentWorkerService.reclaimStale] runs every
 * `nimbus.deployments.reclaim-interval`. Without it, a task held by a crashed worker stays IN_PROGRESS for good, and a
 * cancelled deployment waiting on that task never finishes.
 *
 * Safe to run on several backend instances at once: each task is locked and re-checked before it is touched.
 * Turn it off with `nimbus.deployments.reclaim-enabled=false` (the tests do, so they control when it runs).
 */
@Component
@ConditionalOnProperty(name = ["nimbus.deployments.reclaim-enabled"], havingValue = "true", matchIfMissing = true)
class StaleLeaseReclaimer(private val worker: DeploymentWorkerService) {
    private val log = LoggerFactory.getLogger(javaClass)

    // fixedDelay, so a slow run is never overlapped by the next one.
    @Scheduled(fixedDelayString = "\${nimbus.deployments.reclaim-interval:30s}", initialDelayString = "\${nimbus.deployments.reclaim-interval:30s}")
    fun reclaim() {
        try {
            val handled = worker.reclaimStale()
            if (handled > 0) log.info("Took back {} task(s) whose worker stopped responding", handled)
        } catch (e: Exception) {
            log.error("Reclaiming stale tasks failed; will try again", e)
        }
    }
}
