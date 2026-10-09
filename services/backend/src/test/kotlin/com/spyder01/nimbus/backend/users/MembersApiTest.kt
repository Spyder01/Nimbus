package com.spyder01.nimbus.backend.users

import com.spyder01.nimbus.backend.users.entities.Role
import com.spyder01.nimbus.backend.users.entities.User
import com.spyder01.nimbus.backend.users.repositories.UserRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Everything rolls back, including the users (created in @BeforeEach, inside the test's transaction).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MembersApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val em: EntityManager,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val json: JsonMapper,
) {
    private lateinit var superAdmin: User
    private lateinit var admin: User
    private lateinit var member: User
    private lateinit var other: User

    // A word that no real member's name contains, so a search finds only these test users.
    private val tag = "zz${System.nanoTime() % 1_000_000_000}"

    @BeforeEach
    fun createUsers() {
        superAdmin = users.saveAndFlush(User(name = "$tag Sue", email = "sue@$tag.test", role = Role.SUPER_ADMIN))
        admin = users.saveAndFlush(User(name = "$tag Ann", email = "ann@$tag.test", role = Role.ADMIN))
        member = users.saveAndFlush(User(name = "$tag Mo", email = "mo@$tag.test"))
        other = users.saveAndFlush(User(name = "$tag Zed", email = "zed@$tag.test"))
        identity(member, "GITHUB", "gh-mo-$tag", "mo-the-dev")
    }

    private fun identity(user: User, provider: String, providerUserId: String, login: String?) {
        jdbc.sql("INSERT INTO user_identities (user_id, provider, provider_user_id, login) VALUES (:u, :p, :i, :l)")
            .param("u", user.id).param("p", provider).param("i", providerUserId).param("l", login).update()
    }

    /** Calls as [user], carrying [authorities] in the session (by default the ones their role gives). */
    private fun call(
        user: User, req: MockHttpServletRequestBuilder, body: String? = null, csrf: Boolean = true,
        authorities: List<String> = user.role.authorities().map { requireNotNull(it.authority) },
    ): ResultActions {
        em.flush()
        em.clear()
        var r = req.with(oauth2Login().authorities(authorities.map(::SimpleGrantedAuthority)).attributes { it["userId"] = user.id.toString() })
        if (csrf) r = r.with(csrf())
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(body)
        return mvc.perform(r)
    }

    private fun setRole(actor: User, target: UUID?, role: String) = call(actor, put("/api/admin/members/$target/role"), """{"role":"$role"}""")

    private fun JsonNode.each(): List<JsonNode> = (0 until size()).map { get(it) }

    private fun roleInDb(u: User): String = requireNotNull(jdbc.sql("SELECT role FROM users WHERE id = :id").param("id", u.id).query(String::class.java).single())

    private fun fetch(q: String, size: Int? = null, page: Int? = null): JsonNode {
        var req = get("/api/admin/members").param("q", q)
        if (size != null) req = req.param("size", size.toString())
        if (page != null) req = req.param("page", page.toString())
        return json.readTree(call(admin, req).andExpect(status().isOk).andReturn().response.contentAsString)
    }

    // ---- who may do what ----

    @Test
    fun `admins can look, only super admins can change roles, and others can do neither`() {
        mvc.perform(get("/api/admin/members")).andExpect(status().isUnauthorized)
        call(member, get("/api/admin/members")).andExpect(status().isForbidden)
        call(member, put("/api/admin/members/${other.id}/role"), """{"role":"ADMIN"}""").andExpect(status().isForbidden)

        call(admin, get("/api/admin/members")).andExpect(status().isOk)
        call(superAdmin, get("/api/admin/members")).andExpect(status().isOk)

        setRole(admin, member.id, "ADMIN").andExpect(status().isForbidden)
        assertEquals("USER", roleInDb(member), "a refused request changes nothing")
        setRole(superAdmin, member.id, "ADMIN").andExpect(status().isOk)
        assertEquals("ADMIN", roleInDb(member))
    }

    @Test
    fun `a super admin can do everything an admin can, such as see the workers`() {
        call(superAdmin, get("/api/workers")).andExpect(status().isOk)
        call(admin, get("/api/workers")).andExpect(status().isOk)
        call(member, get("/api/workers")).andExpect(status().isForbidden)
    }

    @Test
    fun `a role change counts straight away, in both directions, whatever the session says`() {
        // Taken away: the session still carries ROLE_ADMIN, but the database says USER.
        call(member, get("/api/workers"), authorities = listOf("ROLE_ADMIN", "ROLE_USER")).andExpect(status().isForbidden)
        call(member, get("/api/admin/members"), authorities = listOf("ROLE_ADMIN", "ROLE_USER")).andExpect(status().isForbidden)
        // Given: the session was made before the promotion and only says USER.
        setRole(superAdmin, member.id, "ADMIN").andExpect(status().isOk)
        call(member, get("/api/workers"), authorities = listOf("ROLE_USER")).andExpect(status().isOk)
        // And a session can't promote itself above what the database says.
        call(admin, put("/api/admin/members/${other.id}/role"), """{"role":"ADMIN"}""", authorities = listOf("ROLE_SUPER_ADMIN", "ROLE_ADMIN", "ROLE_USER"))
            .andExpect(status().isForbidden)
        assertEquals("USER", roleInDb(other))
    }

    // ---- the list ----

    @Test
    fun `lists members by name with their role and how they sign in`() {
        val r = fetch(tag)
        assertEquals(4, r.get("total").asInt())
        val items = r.get("items")
        val names: List<String> = items.each().map { it.get("name").asString() }
        assertEquals(listOf("$tag Ann", "$tag Mo", "$tag Sue", "$tag Zed"), names, "ordered by name")
        val mo = items.each().first { it.get("id").asString() == member.id.toString() }
        assertEquals("USER", mo.get("role").asString())
        assertEquals("GITHUB", mo.get("provider").asString()); assertEquals("mo-the-dev", mo.get("login").asString())
        assertEquals("mo@$tag.test", mo.get("email").asString())
        assertTrue(mo.get("createdAt").isString)
        assertEquals("SUPER_ADMIN", items.each().first { it.get("name").asString().endsWith("Sue") }.get("role").asString())
        assertTrue(items.each().first { it.get("name").asString().endsWith("Zed") }.get("provider").isNull, "someone with no sign-in record")
    }

    @Test
    fun `search matches name, email and sign-in handle, ignoring case, and treats wildcards as plain text`() {
        fun ids(q: String): List<String> = fetch(q).get("items").each().map { it.get("id").asString() }
        assertEquals(listOf(member.id.toString()), ids("$tag MO".uppercase()), "by name, any case")
        assertEquals(listOf(admin.id.toString()), ids("ann@$tag.test"), "by email")
        assertEquals(listOf(member.id.toString()), ids("mo-the-dev"), "by GitHub handle")
        assertEquals(0, fetch("$tag%").get("total").asInt(), "a % is not a wildcard (it would match all four)")
        assertEquals(0, fetch("$tag _").get("total").asInt(), "nor is an underscore (it would match \"$tag Mo\" and the others)")
        assertEquals(0, fetch("$tag\\").get("total").asInt(), "nor is a backslash")
        assertEquals(0, fetch("zzz-nobody-$tag").get("total").asInt())
    }

    @Test
    fun `lists are paged, with the total across pages`() {
        val first = fetch(tag, size = 3, page = 0)
        assertEquals(3, first.get("items").size()); assertEquals(4, first.get("total").asInt())
        assertEquals(0, first.get("page").asInt()); assertEquals(3, first.get("size").asInt())
        val second = fetch(tag, size = 3, page = 1)
        assertEquals(1, second.get("items").size())
        assertEquals(0, fetch(tag, size = 3, page = 5).get("items").size())
        for (bad in listOf("size=0", "size=101", "page=-1")) {
            call(admin, get("/api/admin/members?$bad")).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_paging"))
        }
    }

    // ---- changing roles ----

    @Test
    fun `making someone an admin and taking it away again`() {
        setRole(superAdmin, member.id, "ADMIN").andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(member.id.toString())).andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.login").value("mo-the-dev")) // the whole member comes back, as listed
        assertEquals("ADMIN", roleInDb(member))
        setRole(superAdmin, member.id, "ADMIN").andExpect(status().isOk) // already so: not an error
        setRole(superAdmin, member.id, "USER").andExpect(status().isOk).andExpect(jsonPath("$.role").value("USER"))
        assertEquals("USER", roleInDb(member))
        setRole(superAdmin, admin.id, "USER").andExpect(status().isOk) // an existing admin can be demoted too
        assertEquals("USER", roleInDb(admin))
    }

    @Test
    fun `nobody can change their own role, touch a super admin, or make one`() {
        setRole(superAdmin, superAdmin.id, "USER").andExpect(status().isConflict).andExpect(jsonPath("$.error").value("cannot_change_own_role"))
        assertEquals("SUPER_ADMIN", roleInDb(superAdmin))

        val second = users.saveAndFlush(User(name = "$tag Sam", role = Role.SUPER_ADMIN))
        setRole(superAdmin, second.id, "USER").andExpect(status().isConflict).andExpect(jsonPath("$.error").value("super_admin_protected"))
        assertEquals("SUPER_ADMIN", roleInDb(second))

        setRole(superAdmin, member.id, "SUPER_ADMIN").andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_role"))
        assertEquals("USER", roleInDb(member), "no request can create a super admin")
    }

    @Test
    fun `bad requests are refused and change nothing`() {
        setRole(superAdmin, UUID.randomUUID(), "ADMIN").andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("member_not_found"))
        for (b in listOf("""{}""", """{"role":"OWNER"}""", """{"role":"admin"}""", """{"role":1}""", """{"role":null}""", """{"role":"ADMIN","x":1}""", """["ADMIN"]""", """null""", """not json""")) {
            call(superAdmin, put("/api/admin/members/${member.id}/role"), b).andExpect(status().isBadRequest)
        }
        call(superAdmin, put("/api/admin/members/not-a-uuid/role"), """{"role":"ADMIN"}""").andExpect(status().isBadRequest)
        call(superAdmin, put("/api/admin/members/${member.id}/role"), """{"role":"ADMIN"}""", csrf = false).andExpect(status().isForbidden)
        assertEquals("USER", roleInDb(member))
    }
}
