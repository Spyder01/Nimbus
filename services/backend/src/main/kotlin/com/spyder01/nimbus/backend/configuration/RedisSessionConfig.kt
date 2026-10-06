package com.spyder01.nimbus.backend.configuration

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession

/**
 * Stores HTTP sessions in Redis when REDIS_ENABLED=true; otherwise the container's in-memory sessions are used.
 * Boot's own SessionDataRedisAutoConfiguration is excluded in application.yaml so this toggle is the only switch.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.redis", name = ["enabled"], havingValue = "true")
@EnableRedisHttpSession(redisNamespace = "nimbus:session", maxInactiveIntervalInSeconds = 1800)
class RedisSessionConfig
