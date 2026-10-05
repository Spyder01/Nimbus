package com.spyder01.nimbus.backend.users.controllers

import com.spyder01.nimbus.backend.users.dto.MeResponse
import com.spyder01.nimbus.backend.users.dto.UpdateProfileRequest
import com.spyder01.nimbus.backend.users.repositories.UserRepository
import com.spyder01.nimbus.backend.users.services.UserService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class MeController(
    private val users: UserRepository,
    private val userService: UserService,
) {
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal principal: OAuth2User?,
    ): ResponseEntity<MeResponse> {
        val userId = userIdOf(principal) ?: return ResponseEntity.status(401).build()
        val user = users.findById(userId).orElse(null) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(MeResponse.from(user))
    }

    @PutMapping("/me")
    fun updateMe(
        @AuthenticationPrincipal principal: OAuth2User?,
        @Valid @RequestBody request: UpdateProfileRequest,
    ): ResponseEntity<MeResponse> {
        val userId = userIdOf(principal) ?: return ResponseEntity.status(401).build()
        val user = userService.updateProfile(userId, request) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(MeResponse.from(user))
    }

    private fun userIdOf(principal: OAuth2User?): UUID? =
        principal?.getAttribute<String>("userId")?.let(UUID::fromString)
}
