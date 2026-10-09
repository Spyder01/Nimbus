package com.spyder01.nimbus.backend.users.entities

import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority

/** Each role includes the ones before it, so the order matters: a super admin can do everything an admin can. */
enum class Role {
    USER,
    ADMIN,
    SUPER_ADMIN,
    ;

    /** What the role may do, as Spring Security authorities: its own and every lower one (ROLE_ADMIN implies ROLE_USER). */
    fun authorities(): List<GrantedAuthority> = entries.filter { it.ordinal <= ordinal }.map { SimpleGrantedAuthority("ROLE_${it.name}") }
}
