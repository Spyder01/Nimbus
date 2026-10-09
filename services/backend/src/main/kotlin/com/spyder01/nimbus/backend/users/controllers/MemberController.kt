package com.spyder01.nimbus.backend.users.controllers

import com.spyder01.nimbus.backend.shared.ApiException
import com.spyder01.nimbus.backend.shared.userId
import com.spyder01.nimbus.backend.users.dto.MemberDto
import com.spyder01.nimbus.backend.users.dto.MemberPageDto
import com.spyder01.nimbus.backend.users.entities.Role
import com.spyder01.nimbus.backend.users.services.MemberService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

/** Everyone with an account. Admins can look; only a super admin can change who is an admin. */
@RestController
@RequestMapping("/api/admin/members")
@PreAuthorize("hasRole('ADMIN')")
class MemberController(
    private val service: MemberService,
) {
    /** Members by name, searchable (name, email or sign-in handle) and paged. */
    @GetMapping
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "${MemberService.DEFAULT_SIZE}") size: Int,
    ): MemberPageDto = service.list(q, page, size)

    /**
     * Sets a member's role: `{"role": "ADMIN"}` or `{"role": "USER"}`. It counts straight away, whatever they are doing.
     * Not for yourself, not for a super admin, and a super admin can't be made here.
     */
    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    fun setRole(
        @AuthenticationPrincipal user: OAuth2User,
        @PathVariable id: UUID,
        @RequestBody body: JsonNode,
    ): MemberDto {
        val invalid = ApiException(HttpStatus.BAD_REQUEST, "invalid_body", """The body must be {"role": "ADMIN"} or {"role": "USER"}""")
        if (!body.isObject || body.propertyNames().toSet() != setOf("role")) throw invalid
        val name = body.get("role").takeIf { it.isString }?.stringValue() ?: throw invalid
        val role = Role.entries.firstOrNull { it.name == name } ?: throw invalid
        return service.setRole(user.userId(), id, role)
    }
}
