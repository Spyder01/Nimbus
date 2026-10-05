package com.spyder01.nimbus.backend.configuration

import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * Serves the embedded UI (classpath:/static, copied from frontend/dist at build time).
 * Real files are served as-is; any other non-API path falls back to index.html so client-side
 * routes like /settings survive a refresh or a direct link.
 */
@Configuration
class SpaConfig : WebMvcConfigurer {
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/**")
            .addResourceLocations("classpath:/static/")
            .resourceChain(true)
            .addResolver(
                object : PathResourceResolver() {
                    override fun getResource(resourcePath: String, location: Resource): Resource? {
                        val requested = location.createRelative(resourcePath)
                        if (requested.exists() && requested.isReadable) return requested
                        if (API_PREFIXES.any { resourcePath == it || resourcePath.startsWith("$it/") }) return null
                        return ClassPathResource("static/index.html").takeIf { it.exists() }
                    }
                },
            )
    }

    private companion object {
        // Server-side routes: unknown paths under these must 404, not return the UI.
        val API_PREFIXES = listOf("api", "actuator", "oauth2", "login", "logout")
    }
}
