package com.spyder01.nimbus.backend.users.services

import com.spyder01.nimbus.backend.shared.ApiException
import com.spyder01.nimbus.backend.users.dto.MemberDto
import com.spyder01.nimbus.backend.users.dto.MemberPageDto
import com.spyder01.nimbus.backend.users.entities.Role
import com.spyder01.nimbus.backend.users.repositories.MemberRepository
import com.spyder01.nimbus.backend.users.repositories.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class MemberService(
    private val members: MemberRepository,
    private val users: UserRepository,
) {
    companion object {
        const val DEFAULT_SIZE = 25
        const val MAX_SIZE = 100
    }

    @Transactional(readOnly = true)
    fun list(q: String?, page: Int, size: Int): MemberPageDto {
        if (page < 0 || size !in 1..MAX_SIZE) {
            throw ApiException(HttpStatus.BAD_REQUEST, "invalid_paging", "page must be 0 or more, and size between 1 and $MAX_SIZE")
        }
        return MemberPageDto(members.search(q, size, page * size), members.count(q), page, size)
    }

    /**
     * Makes someone an admin, or takes admin away. Only the two ordinary roles can be given here: a super admin is
     * made in the database, so no request can create one, and nobody can take that role away through the API either.
     * You can't change your own role (so the last person who can manage roles can't lock themselves out).
     */
    @Transactional
    fun setRole(actor: UUID, target: UUID, role: Role): MemberDto {
        if (role == Role.SUPER_ADMIN) {
            throw ApiException(HttpStatus.BAD_REQUEST, "invalid_role", "Only USER or ADMIN can be given here")
        }
        val user = users.findById(target).orElse(null) ?: throw ApiException(HttpStatus.NOT_FOUND, "member_not_found", "No such member")
        if (target == actor) {
            throw ApiException(HttpStatus.CONFLICT, "cannot_change_own_role", "You can't change your own role")
        }
        if (user.role == Role.SUPER_ADMIN) {
            throw ApiException(HttpStatus.CONFLICT, "super_admin_protected", "A super admin's role can't be changed here")
        }
        if (user.role != role) users.saveAndFlush(user.also { it.role = role })
        return requireNotNull(members.find(target))
    }
}
