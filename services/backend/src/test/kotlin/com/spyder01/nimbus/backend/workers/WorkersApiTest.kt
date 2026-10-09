package com.spyder01.nimbus.backend.workers

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
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Everything rolls back (including the users, created in @BeforeEach so they are inside the test's transaction).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkersApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val em: EntityManager,
    @Autowired private val jdbc: JdbcClient,
    @Autowired private val json: JsonMapper,
) {
    private lateinit var admin: User
    private lateinit var member: User
    private val pool = "zz-${System.nanoTime() % 1_000_000_000}"

    @BeforeEach
    fun createUsers() {
        admin = users.saveAndFlush(User(name = "admin", role = Role.ADMIN))
        member = users.saveAndFlush(User(name = "member"))
    }

    private fun call(user: User, req: MockHttpServletRequestBuilder, body: String? = null, csrf: Boolean = true): ResultActions {
        em.flush()
        em.clear()
        var r = req.with(
            oauth2Login()
                .authorities(SimpleGrantedAuthority("ROLE_${user.role.name}"))
                .attributes { it["userId"] = user.id.toString() },
        )
        if (csrf) r = r.with(csrf())
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(body)
        return mvc.perform(r)
    }

    private fun asAdmin(req: MockHttpServletRequestBuilder, body: String? = null) = call(admin, req, body)

    private fun slot(name: String, ordinal: Int?, instance: String, live: Boolean = true, applied: Long? = null, problem: String? = null, poolName: String = pool) {
        jdbc.sql(
            """INSERT INTO worker_slots (name, pool, ordinal, instance_id, version, host, lease_expires_at, applied_settings_version, settings_error)
               VALUES (:n, :p, :o, :i, '1.2.3', 'host-1', clock_timestamp() + CAST(:d AS interval), :a, :e)""",
        ).param("n", name).param("p", poolName).param("o", ordinal, java.sql.Types.INTEGER).param("i", instance)
            .param("d", if (live) "30 seconds" else "-30 seconds").param("a", applied, java.sql.Types.BIGINT).param("e", problem).update()
    }

    private fun stored(scope: String, name: String, jobs: Int?, lease: Int?) {
        jdbc.sql("INSERT INTO worker_settings (scope, name, parallel_jobs, lease_seconds) VALUES (:s, :n, :j, :l)")
            .param("s", scope).param("n", name).param("j", jobs, java.sql.Types.INTEGER).param("l", lease, java.sql.Types.INTEGER).update()
    }

    private fun version(scope: String, name: String): Long =
        jdbc.sql("SELECT version FROM worker_settings WHERE scope = :s AND name = :n").param("s", scope).param("n", name).query(Long::class.java).single()

    private fun settingsOf(name: String) = json.readTree(asAdmin(get("/api/workers/$name/settings")).andReturn().response.contentAsString).get("settings")

    // ---- who may call ----

    @Test
    fun `only signed-in admins can use these endpoints`() {
        slot("$pool-0", 0, "i-0")
        val urls = listOf("/api/workers", "/api/workers/$pool-0", "/api/workers/$pool-0/settings")
        for (u in urls) mvc.perform(get(u)).andExpect(status().isUnauthorized)
        for (u in urls) call(member, get(u)).andExpect(status().isForbidden)
        call(member, put("/api/workers/$pool-0/settings"), """{"parallelJobs":3,"leaseSeconds":null}""").andExpect(status().isForbidden)
        for (u in urls) asAdmin(get(u)).andExpect(status().isOk)
        assertEquals(0, jdbc.sql("SELECT count(*) FROM worker_settings WHERE name = :n").param("n", "$pool-0").query(Int::class.java).single(), "a refused request must write nothing")
    }

    // ---- listing ----

    @Test
    fun `lists workers with status, ordering and the jobs each is running`() {
        slot("$pool-0", 0, "i-a")
        slot("$pool-1", 1, "i-b", live = false)
        slot("$pool-gpu", null, "i-c") // asked for its name: no ordinal, listed after the numbered ones
        slot("other-0", 0, "i-d", poolName = "zz-other-$pool")

        val app = jdbc.sql("INSERT INTO apps (owner_id, name, state) VALUES (:u, :n, 'DEPLOYING') RETURNING id").param("u", admin.id).param("n", "zz-$pool").query(UUID::class.java).single()
        val spec = jdbc.sql("INSERT INTO app_specs (app_id, kind, revision, content_hash) VALUES (:a, 'SAVED', 1, 'x') RETURNING id").param("a", app).query(UUID::class.java).single()
        val dep = jdbc.sql("INSERT INTO deployments (app_id, spec_id, state) VALUES (:a, :s, 'IN_PROGRESS') RETURNING id").param("a", app).param("s", spec).query(UUID::class.java).single()
        fun task(name: String, state: String, owner: String?) =
            jdbc.sql("INSERT INTO deployment_tasks (deployment_id, node_id, name, layer, ordinal, spec, state, lease_owner) VALUES (:d, :n, :n, 0, 0, '{}', :s, :o)")
                .param("d", dep).param("n", name).param("s", state).param("o", owner).update()
        task("t1", "IN_PROGRESS", "i-a"); task("t2", "IN_PROGRESS", "i-a"); task("t3", "SUCCEEDED", "i-a"); task("t4", "IN_PROGRESS", "someone-else")

        asAdmin(get("/api/workers").param("pool", pool))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].name").value("$pool-0"))
            .andExpect(jsonPath("$[1].name").value("$pool-1"))
            .andExpect(jsonPath("$[2].name").value("$pool-gpu"))
            .andExpect(jsonPath("$[0].status").value("ONLINE")).andExpect(jsonPath("$[1].status").value("OFFLINE")).andExpect(jsonPath("$[2].status").value("ONLINE"))
            .andExpect(jsonPath("$[0].heldJobs").value(2)) // finished jobs and other workers' jobs don't count
            .andExpect(jsonPath("$[1].heldJobs").value(0))
            .andExpect(jsonPath("$[0].ordinal").value(0)).andExpect(jsonPath("$[2].ordinal").doesNotExist())
            .andExpect(jsonPath("$[0].pool").value(pool)).andExpect(jsonPath("$[0].instanceId").value("i-a"))
            .andExpect(jsonPath("$[0].version").value("1.2.3")).andExpect(jsonPath("$[0].host").value("host-1"))
            .andExpect(jsonPath("$[0].startedAt").exists()).andExpect(jsonPath("$[0].lastSeenAt").exists()).andExpect(jsonPath("$[0].leaseExpiresAt").exists())

        asAdmin(get("/api/workers").param("pool", "zz-nothing-here")).andExpect(jsonPath("$.length()").value(0))
        val names = ArrayList<String>()
        for (w in json.readTree(asAdmin(get("/api/workers")).andReturn().response.contentAsString)) names += w.get("name").asString()
        assertTrue(names.containsAll(listOf("$pool-0", "$pool-1", "$pool-gpu", "other-0")), "the unfiltered list includes every pool: $names")
    }

    @Test
    fun `one worker by name, or 404`() {
        slot("$pool-0", 0, "i-a")
        asAdmin(get("/api/workers/$pool-0")).andExpect(status().isOk).andExpect(jsonPath("$.name").value("$pool-0")).andExpect(jsonPath("$.settings").exists())
        asAdmin(get("/api/workers/$pool-9")).andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("worker_not_found"))
        asAdmin(get("/api/workers/$pool-9/settings")).andExpect(status().isNotFound)
    }

    // ---- how settings combine ----

    @Test
    fun `nothing stored means nothing to apply`() {
        slot("$pool-0", 0, "i-a", applied = 0)
        asAdmin(get("/api/workers/$pool-0/settings")).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("$pool-0")).andExpect(jsonPath("$.pool").value(pool))
            .andExpect(jsonPath("$.settings.override.parallelJobs").doesNotExist())
            .andExpect(jsonPath("$.settings.effective.parallelJobs").doesNotExist()).andExpect(jsonPath("$.settings.effective.leaseSeconds").doesNotExist())
            .andExpect(jsonPath("$.settings.desiredVersion").value(0)).andExpect(jsonPath("$.settings.appliedVersion").value(0))
            .andExpect(jsonPath("$.settings.inSync").value(true))
    }

    @Test
    fun `a workers own value beats its pool and a null inherits`() {
        slot("$pool-0", 0, "i-a")
        stored("POOL", pool, 7, 900)
        stored("WORKER", "$pool-0", 3, null)
        val s = settingsOf("$pool-0")
        assertEquals(3, s.get("override").get("parallelJobs").asInt())
        assertTrue(s.get("override").get("leaseSeconds").isNull)
        assertEquals(7, s.get("poolDefault").get("parallelJobs").asInt())
        assertEquals(3, s.get("effective").get("parallelJobs").asInt(), "own value wins")
        assertEquals(900, s.get("effective").get("leaseSeconds").asInt(), "a null inherits the pool's")
        assertEquals(maxOf(version("POOL", pool), version("WORKER", "$pool-0")), s.get("desiredVersion").asLong())
    }

    @Test
    fun `a worker is in sync only when it has applied the latest stored version`() {
        stored("POOL", pool, 5, null)
        val v = version("POOL", pool)
        slot("$pool-0", 0, "i-behind", applied = v - 1)
        slot("$pool-1", 1, "i-current", applied = v)
        slot("$pool-2", 2, "i-never", applied = null)
        slot("$pool-3", 3, "i-refused", applied = v, problem = "version $v refused: boom")
        fun sync(n: String) = settingsOf(n).get("inSync").asBoolean()
        assertEquals(listOf(false, true, false, false), listOf(sync("$pool-0"), sync("$pool-1"), sync("$pool-2"), sync("$pool-3")))
        assertEquals("version $v refused: boom", settingsOf("$pool-3").get("problem").asString())
        assertTrue(settingsOf("$pool-1").get("problem").isNull)
    }

    // ---- changing a worker's settings ----

    @Test
    fun `putting settings replaces the override, records who, and gives the change a new version`() {
        slot("$pool-0", 0, "i-a", applied = 0)
        stored("POOL", pool, 7, 900)
        val before = settingsOf("$pool-0").get("desiredVersion").asLong()

        asAdmin(put("/api/workers/$pool-0/settings"), """{"parallelJobs": 8, "leaseSeconds": 120}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.settings.override.parallelJobs").value(8)).andExpect(jsonPath("$.settings.override.leaseSeconds").value(120))
            .andExpect(jsonPath("$.settings.effective.parallelJobs").value(8)).andExpect(jsonPath("$.settings.effective.leaseSeconds").value(120))
            .andExpect(jsonPath("$.settings.poolDefault.parallelJobs").value(7)) // the pool's default is untouched
            .andExpect(jsonPath("$.settings.overrideUpdatedBy").value(admin.id.toString())).andExpect(jsonPath("$.settings.overrideUpdatedAt").exists())
            .andExpect(jsonPath("$.settings.inSync").value(false)) // the worker hasn't picked it up yet

        val after = settingsOf("$pool-0")
        assertTrue(after.get("desiredVersion").asLong() > before, "a change gets a higher version, so the worker notices it")
        assertEquals(8, after.get("override").get("parallelJobs").asInt(), "it was saved, not just echoed")
        assertEquals(7, jdbc.sql("SELECT parallel_jobs FROM worker_settings WHERE scope='POOL' AND name=:n").param("n", pool).query(Int::class.java).single())
    }

    @Test
    fun `putting again replaces the whole override, and nulls go back to inheriting`() {
        slot("$pool-0", 0, "i-a")
        stored("POOL", pool, 7, 900)
        asAdmin(put("/api/workers/$pool-0/settings"), """{"parallelJobs": 5, "leaseSeconds": 120}""").andExpect(status().isOk)
        val v1 = settingsOf("$pool-0").get("desiredVersion").asLong()

        asAdmin(put("/api/workers/$pool-0/settings"), """{"parallelJobs": 3, "leaseSeconds": null}""")
            .andExpect(jsonPath("$.settings.override.parallelJobs").value(3))
            .andExpect(jsonPath("$.settings.override.leaseSeconds").doesNotExist())
            .andExpect(jsonPath("$.settings.effective.leaseSeconds").value(900)) // the pool's again

        asAdmin(put("/api/workers/$pool-0/settings"), """{"parallelJobs": null, "leaseSeconds": null}""")
            .andExpect(jsonPath("$.settings.effective.parallelJobs").value(7))
        val v3 = settingsOf("$pool-0").get("desiredVersion").asLong()
        assertTrue(v3 > v1, "clearing is a change too, and gets a new version")
        assertEquals(1, jdbc.sql("SELECT count(*) FROM worker_settings WHERE scope='WORKER' AND name=:n").param("n", "$pool-0").query(Int::class.java).single(), "the row stays, with NULLs")
    }

    @Test
    fun `values out of range are refused with every problem listed, and nothing changes`() {
        slot("$pool-0", 0, "i-a")
        asAdmin(put("/api/workers/$pool-0/settings"), """{"parallelJobs": 5, "leaseSeconds": 120}""").andExpect(status().isOk)
        for ((jobs, lease) in listOf(0 to 120, 101 to 120, 5 to 59, 5 to 86401, -1 to -1)) {
            asAdmin(put("/api/workers/$pool-0/settings"), """{"parallelJobs": $jobs, "leaseSeconds": $lease}""")
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_settings"))
        }
        asAdmin(put("/api/workers/$pool-0/settings"), """{"parallelJobs": 0, "leaseSeconds": 5}""")
            .andExpect(jsonPath("$.errors.length()").value(2)).andExpect(jsonPath("$.errors[0].field").value("parallelJobs")).andExpect(jsonPath("$.errors[1].field").value("leaseSeconds"))
        asAdmin(put("/api/workers/$pool-0/settings"), """{"parallelJobs": 1, "leaseSeconds": 60}""").andExpect(status().isOk) // the limits themselves are fine
        asAdmin(put("/api/workers/$pool-0/settings"), """{"parallelJobs": 100, "leaseSeconds": 86400}""").andExpect(status().isOk)
    }

    @Test
    fun `a body that isn't exactly both fields is refused instead of clearing the override by accident`() {
        slot("$pool-0", 0, "i-a")
        asAdmin(put("/api/workers/$pool-0/settings"), """{"parallelJobs": 5, "leaseSeconds": 120}""").andExpect(status().isOk)
        val bad = listOf(
            """{}""", """{"parallelJobs": 3}""", """{"leaseSeconds": 200}""",                    // a missing field
            """{"parallelJob": 3, "leaseSeconds": 200}""",                                     // a typo
            """{"parallelJobs": 3, "leaseSeconds": 200, "extra": 1}""",                        // something unknown
            """{"parallelJobs": "5", "leaseSeconds": 200}""", """{"parallelJobs": 4.5, "leaseSeconds": 200}""",
            """{"parallelJobs": true, "leaseSeconds": 200}""", """{"parallelJobs": [3], "leaseSeconds": 200}""",
            """{"parallelJobs": 99999999999, "leaseSeconds": 200}""",
            """[1,2]""", """null""", """"text"""", """not json""",
        )
        for (b in bad) {
            asAdmin(put("/api/workers/$pool-0/settings"), b).andExpect(status().isBadRequest)
        }
        val s = settingsOf("$pool-0").get("override")
        assertEquals(5, s.get("parallelJobs").asInt()); assertEquals(120, s.get("leaseSeconds").asInt()) // all of that changed nothing
    }

    @Test
    fun `settings can only be put for a worker that exists, with a csrf token`() {
        asAdmin(put("/api/workers/$pool-9/settings"), """{"parallelJobs": 3, "leaseSeconds": null}""").andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("worker_not_found"))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM worker_settings WHERE name = :n").param("n", "$pool-9").query(Int::class.java).single(), "no row for a worker that doesn't exist")
        slot("$pool-0", 0, "i-a")
        call(admin, put("/api/workers/$pool-0/settings"), """{"parallelJobs": 3, "leaseSeconds": null}""", csrf = false).andExpect(status().isForbidden)
        assertNotNull(admin.id)
    }

    // ---- pools and their defaults ----

    private fun poolOf(name: String) = json.readTree(asAdmin(get("/api/worker-pools/$name")).andReturn().response.contentAsString)

    @Test
    fun `only signed-in admins can use the pool endpoints`() {
        slot("$pool-0", 0, "i-0")
        val urls = listOf("/api/worker-pools", "/api/worker-pools/$pool")
        for (u in urls) mvc.perform(get(u)).andExpect(status().isUnauthorized)
        for (u in urls) call(member, get(u)).andExpect(status().isForbidden)
        call(member, put("/api/worker-pools/$pool/settings"), """{"parallelJobs":3,"leaseSeconds":null}""").andExpect(status().isForbidden)
        for (u in urls) asAdmin(get(u)).andExpect(status().isOk)
        assertEquals(0, jdbc.sql("SELECT count(*) FROM worker_settings WHERE name = :n").param("n", pool).query(Int::class.java).single(), "a refused request must write nothing")
    }

    @Test
    fun `lists pools with their workers, defaults and how many workers override them`() {
        slot("$pool-0", 0, "i-a")
        slot("$pool-1", 1, "i-b", live = false)
        slot("$pool-2", 2, "i-c")
        slot("o-0", 0, "i-d", poolName = "zz-other-$pool")
        stored("POOL", pool, 7, null)
        stored("WORKER", "$pool-0", 3, null)       // overrides a setting
        stored("WORKER", "$pool-1", null, null)    // a row, but it overrides nothing
        stored("POOL", "zz-empty-$pool", 2, 300)   // defaults set before any worker joined

        val all = json.readTree(asAdmin(get("/api/worker-pools")).andReturn().response.contentAsString).filter { it.get("name").asString().endsWith(pool) }
        assertEquals(listOf("zz-empty-$pool", "zz-other-$pool", pool).sorted(), all.map { it.get("name").asString() }, "ordered by name, including a pool that only has defaults")

        val p = all.first { it.get("name").asString() == pool }
        assertEquals(3, p.get("workers").asInt()); assertEquals(2, p.get("online").asInt()); assertEquals(1, p.get("overriding").asInt())
        assertEquals(7, p.get("defaults").get("parallelJobs").asInt()); assertTrue(p.get("defaults").get("leaseSeconds").isNull)

        val empty = all.first { it.get("name").asString() == "zz-empty-$pool" }
        assertEquals(0, empty.get("workers").asInt()); assertEquals(300, empty.get("defaults").get("leaseSeconds").asInt())
        val noDefaults = all.first { it.get("name").asString() == "zz-other-$pool" }
        assertTrue(noDefaults.get("defaults").get("parallelJobs").isNull); assertTrue(noDefaults.get("updatedAt").isNull)
    }

    @Test
    fun `one pool by name, or 404`() {
        slot("$pool-0", 0, "i-a")
        asAdmin(get("/api/worker-pools/$pool")).andExpect(status().isOk).andExpect(jsonPath("$.name").value(pool))
        asAdmin(get("/api/worker-pools/zz-nope-$pool")).andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("pool_not_found"))
    }

    @Test
    fun `putting pool defaults saves them, records who, and every worker without its own value follows`() {
        slot("$pool-0", 0, "i-a", applied = 0)
        slot("$pool-1", 1, "i-b", applied = 0)
        stored("WORKER", "$pool-1", 3, null)
        val before = settingsOf("$pool-0").get("desiredVersion").asLong()

        asAdmin(put("/api/worker-pools/$pool/settings"), """{"parallelJobs": 9, "leaseSeconds": 600}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.defaults.parallelJobs").value(9)).andExpect(jsonPath("$.defaults.leaseSeconds").value(600))
            .andExpect(jsonPath("$.updatedBy").value(admin.id.toString())).andExpect(jsonPath("$.updatedAt").exists())
            .andExpect(jsonPath("$.workers").value(2)).andExpect(jsonPath("$.overriding").value(1))

        val follower = settingsOf("$pool-0")
        assertEquals(9, follower.get("effective").get("parallelJobs").asInt()); assertEquals(600, follower.get("effective").get("leaseSeconds").asInt())
        assertTrue(follower.get("desiredVersion").asLong() > before, "a new version, so the worker notices")
        assertFalse(follower.get("inSync").asBoolean(), "it has not applied the change yet")
        val own = settingsOf("$pool-1")
        assertEquals(3, own.get("effective").get("parallelJobs").asInt(), "a worker's own value still wins")
        assertEquals(600, own.get("effective").get("leaseSeconds").asInt(), "but it follows the pool for what it didn't set")
    }

    @Test
    fun `putting defaults again replaces both, and nulls go back to each workers startup value`() {
        slot("$pool-0", 0, "i-a")
        asAdmin(put("/api/worker-pools/$pool/settings"), """{"parallelJobs": 9, "leaseSeconds": 600}""").andExpect(status().isOk)
        val v1 = settingsOf("$pool-0").get("desiredVersion").asLong()
        asAdmin(put("/api/worker-pools/$pool/settings"), """{"parallelJobs": 4, "leaseSeconds": null}""")
            .andExpect(jsonPath("$.defaults.parallelJobs").value(4)).andExpect(jsonPath("$.defaults.leaseSeconds").doesNotExist())
        asAdmin(put("/api/worker-pools/$pool/settings"), """{"parallelJobs": null, "leaseSeconds": null}""").andExpect(status().isOk)
        assertTrue(settingsOf("$pool-0").get("desiredVersion").asLong() > v1, "clearing is a change too")
        assertTrue(settingsOf("$pool-0").get("effective").get("parallelJobs").isNull)
        assertEquals(1, jdbc.sql("SELECT count(*) FROM worker_settings WHERE scope='POOL' AND name=:n").param("n", pool).query(Int::class.java).single(), "one row, with NULLs")
    }

    @Test
    fun `pool defaults can be set before any worker joins, if defaults already exist`() {
        stored("POOL", pool, 2, null)
        asAdmin(put("/api/worker-pools/$pool/settings"), """{"parallelJobs": 6, "leaseSeconds": null}""").andExpect(status().isOk).andExpect(jsonPath("$.workers").value(0))
        assertEquals(6, poolOf(pool).get("defaults").get("parallelJobs").asInt())
    }

    @Test
    fun `bad pool settings are refused and change nothing`() {
        slot("$pool-0", 0, "i-a")
        asAdmin(put("/api/worker-pools/$pool/settings"), """{"parallelJobs": 5, "leaseSeconds": 120}""").andExpect(status().isOk)
        for ((jobs, lease) in listOf(0 to 120, 101 to 120, 5 to 59, 5 to 86401)) {
            asAdmin(put("/api/worker-pools/$pool/settings"), """{"parallelJobs": $jobs, "leaseSeconds": $lease}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_settings"))
        }
        asAdmin(put("/api/worker-pools/$pool/settings"), """{"parallelJobs": 0, "leaseSeconds": 5}""").andExpect(jsonPath("$.errors.length()").value(2))
        for (b in listOf("""{}""", """{"parallelJobs": 3}""", """{"parallelJob": 3, "leaseSeconds": 200}""", """{"parallelJobs": 3, "leaseSeconds": 200, "x": 1}""", """{"parallelJobs": "5", "leaseSeconds": 200}""", """[1]""", """not json""")) {
            asAdmin(put("/api/worker-pools/$pool/settings"), b).andExpect(status().isBadRequest)
        }
        val d = poolOf(pool).get("defaults")
        assertEquals(5, d.get("parallelJobs").asInt()); assertEquals(120, d.get("leaseSeconds").asInt())
    }

    @Test
    fun `defaults can only be put for a pool that exists, with a csrf token`() {
        asAdmin(put("/api/worker-pools/zz-nope-$pool/settings"), """{"parallelJobs": 3, "leaseSeconds": null}""").andExpect(status().isNotFound).andExpect(jsonPath("$.error").value("pool_not_found"))
        assertEquals(0, jdbc.sql("SELECT count(*) FROM worker_settings WHERE name = :n").param("n", "zz-nope-$pool").query(Int::class.java).single(), "a typo must not invent a pool")
        slot("$pool-0", 0, "i-a")
        call(admin, put("/api/worker-pools/$pool/settings"), """{"parallelJobs": 3, "leaseSeconds": null}""", csrf = false).andExpect(status().isForbidden)
    }

    @Test
    fun `a worker named pools is still reachable, because pools live under their own path`() {
        slot("pools", null, "i-x", poolName = pool)
        asAdmin(get("/api/workers/pools")).andExpect(status().isOk).andExpect(jsonPath("$.name").value("pools"))
        asAdmin(get("/api/worker-pools")).andExpect(status().isOk)
    }
}
