package com.spyder01.nimbus.backend.users.repositories

import com.spyder01.nimbus.backend.users.dto.MemberDto
import com.spyder01.nimbus.backend.users.entities.Role
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Reads the member list: users with how they sign in. Plain SQL, since it joins and searches across tables. */
@Repository
class MemberRepository(
    private val jdbc: JdbcClient,
) {
    private companion object {
        // The first sign-in method stands for the person; most have exactly one.
        const val FROM = """
            FROM users u
            LEFT JOIN LATERAL (
                SELECT provider, login FROM user_identities WHERE user_id = u.id ORDER BY created_at, id LIMIT 1
            ) i ON true
            WHERE (CAST(:pattern AS TEXT) IS NULL
                   OR u.name ILIKE CAST(:pattern AS TEXT) ESCAPE '\'
                   OR u.email ILIKE CAST(:pattern AS TEXT) ESCAPE '\'
                   OR i.login ILIKE CAST(:pattern AS TEXT) ESCAPE '\')
              AND (CAST(:id AS UUID) IS NULL OR u.id = CAST(:id AS UUID))"""

        /** `100%` in a search means the characters, not a wildcard. */
        fun like(q: String?): String? =
            q?.trim()?.takeIf { it.isNotEmpty() }?.let { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
    }

    fun search(q: String?, limit: Int, offset: Int): List<MemberDto> =
        jdbc.sql(
            """
            SELECT u.id, u.name, u.email, u.avatar_url, u.role, u.profile_updated, u.created_at, i.provider, i.login
            $FROM
            ORDER BY lower(coalesce(u.name, i.login, '')), u.created_at, u.id
            LIMIT :limit OFFSET :offset
            """.trimIndent(),
        )
            .param("pattern", like(q)).param("id", null, java.sql.Types.OTHER).param("limit", limit).param("offset", offset)
            .query { rs, _ ->
                MemberDto(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    email = rs.getString("email"),
                    avatarUrl = rs.getString("avatar_url"),
                    role = Role.valueOf(rs.getString("role")),
                    provider = rs.getString("provider"),
                    login = rs.getString("login"),
                    profileUpdated = rs.getBoolean("profile_updated"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }
            .list()

    fun count(q: String?): Long =
        jdbc.sql("SELECT count(*) $FROM")
            .param("pattern", like(q)).param("id", null, java.sql.Types.OTHER)
            .query(Long::class.java).single()

    fun find(id: UUID): MemberDto? =
        jdbc.sql(
            """
            SELECT u.id, u.name, u.email, u.avatar_url, u.role, u.profile_updated, u.created_at, i.provider, i.login
            $FROM
            """.trimIndent(),
        )
            .param("pattern", null, java.sql.Types.VARCHAR).param("id", id)
            .query { rs, _ ->
                MemberDto(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    email = rs.getString("email"),
                    avatarUrl = rs.getString("avatar_url"),
                    role = Role.valueOf(rs.getString("role")),
                    provider = rs.getString("provider"),
                    login = rs.getString("login"),
                    profileUpdated = rs.getBoolean("profile_updated"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }
            .optional().orElse(null)
}
