package com.spyder01.nimbus.backend.configuration

import com.spyder01.nimbus.backend.users.repositories.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Takes the signed-in user's permissions from the database on every request, not from the session.
 *
 * The role is copied into the session when someone logs in, so without this a role change (a promotion, or taking admin
 * away) would only count after that person signed in again, and a demoted admin would keep admin access for as long as
 * their session lasted. The change is for this request only; the session itself is left alone.
 *
 * A user the database doesn't know is left as the session has them.
 */
class FreshRoleFilter(private val users: UserRepository) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val auth = SecurityContextHolder.getContext().authentication
        if (auth is OAuth2AuthenticationToken) {
            val id = auth.principal.getAttribute<String>("userId")?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val authorities = id?.let { users.roleOf(it) }?.authorities()
            if (authorities != null && authorities.toSet() != auth.authorities.toSet()) {
                // Same attributes and name as the real principal; only the authorities differ. A real (serialisable)
                // principal, because anything that stores the context in a session has to be able to write it.
                val principal = auth.principal
                val nameKey = principal.attributes.entries.firstOrNull { it.value?.toString() == principal.name }?.key ?: "userId"
                val refreshed = DefaultOAuth2User(authorities, principal.attributes, nameKey)
                val context = SecurityContextHolder.createEmptyContext()
                context.authentication = OAuth2AuthenticationToken(refreshed, authorities, auth.authorizedClientRegistrationId)
                SecurityContextHolder.setContext(context)
            }
        }
        chain.doFilter(request, response)
    }
}
