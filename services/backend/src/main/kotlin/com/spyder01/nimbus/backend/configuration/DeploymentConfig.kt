package com.spyder01.nimbus.backend.configuration

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/** `nimbus.deployments.*` in application.yaml. */
@ConfigurationProperties(prefix = "nimbus.deployments")
data class DeploymentProperties(
    /** Times a deployment may be claimed in total; a stale lease on the last attempt marks it FAILED. */
    val maxAttempts: Int = 3,
    /** Fixed lease length: a claimed deployment must finish within this, heartbeats don't extend it. */
    val leaseDuration: Duration = Duration.ofMinutes(30),
    /** A claimed deployment whose last heartbeat is older than this is considered abandoned. */
    val heartbeatTimeout: Duration = Duration.ofMinutes(2),
) {
    init {
        require(maxAttempts >= 1) { "nimbus.deployments.max-attempts must be at least 1" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "nimbus.deployments.lease-duration must be positive" }
        require(!heartbeatTimeout.isNegative && !heartbeatTimeout.isZero) { "nimbus.deployments.heartbeat-timeout must be positive" }
    }
}

@Configuration
@EnableConfigurationProperties(DeploymentProperties::class)
class DeploymentConfig {
    /** One clock for lease arithmetic so tests can move time. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
