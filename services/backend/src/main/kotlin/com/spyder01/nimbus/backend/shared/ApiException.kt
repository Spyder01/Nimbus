package com.spyder01.nimbus.backend.shared

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.user.OAuth2User
import java.util.UUID

/** An error the API reports to the client as `{error, message, ...details}`. */
class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)

/** The signed-in user's id, as put on the principal by OAuth2LoginService. */
fun OAuth2User.userId(): UUID =
    getAttribute<String>("userId")?.let(UUID::fromString)
        ?: throw ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Not signed in")
