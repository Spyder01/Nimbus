package com.spyder01.nimbus.backend.apps

import com.spyder01.nimbus.backend.users.entities.User
import com.spyder01.nimbus.backend.users.repositories.UserRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// @Transactional rolls everything back. flush()+clear() between calls forces real SQL and a fresh read,
// so the JSONB mapping is exercised instead of the persistence-context cache.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AppsApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val em: EntityManager,
    @Autowired private val json: JsonMapper,
) {
    private lateinit var alice: User
    private lateinit var bob: User

    // Created in @BeforeEach, which runs inside the test's transaction, so these rows are rolled back with it.
    // (As property initializers they ran before the transaction began and stayed in the database for good.)
    @BeforeEach
    fun createUsers() {
        alice = users.saveAndFlush(User(name = "alice"))
        bob = users.saveAndFlush(User(name = "bob"))
    }

    private fun call(user: User, req: MockHttpServletRequestBuilder, body: Any? = null): ResultActions {
        em.flush()
        em.clear()
        val r = req.with(oauth2Login().attributes { it["userId"] = user.id.toString() }).with(csrf())
        if (body != null) r.contentType(MediaType.APPLICATION_JSON).content(if (body is String) body else json.writeValueAsString(body))
        return mvc.perform(r)
    }

    private fun createApp(user: User = alice, name: String = "shop"): String =
        json.readTree(call(user, post("/api/apps"), mapOf("name" to name)).andExpect(status().isCreated).andReturn().response.contentAsString)
            .get("id").asString()

    private fun node(id: String, name: String = id, image: String = "nginx:1.27", extra: Map<String, Any?> = emptyMap()) =
        mapOf(
            "id" to id, "type" to "container", "position" to mapOf("x" to 10.5, "y" to 20),
            "selected" to true, // React Flow noise: must be ignored
            "data" to (mapOf("name" to name, "image" to image, "port" to 80, "replicas" to 2) + extra),
        )

    private fun edge(id: String, s: String, t: String) = mapOf("id" to id, "source" to s, "target" to t)

    private fun draft(nodes: List<Any>, edges: List<Any> = emptyList(), base: Long) =
        mapOf("nodes" to nodes, "edges" to edges, "baseRevision" to base)

    // ---- apps ----

    @Test
    fun `list is empty then shows created apps newest first with component counts`() {
        call(alice, get("/api/apps")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))

        val a = createApp(name = "first")
        createApp(name = "second")
        call(alice, put("/api/apps/$a/draft"), draft(listOf(node("n1"), node("n2")), base = 0)).andExpect(status().isOk)

        call(alice, get("/api/apps"))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].name").value("first")) // touched last, so first in the list
            .andExpect(jsonPath("$[0].componentCount").value(2))
            .andExpect(jsonPath("$[0].state").value("DRAFT"))
            .andExpect(jsonPath("$[1].componentCount").value(0))
    }

    @Test
    fun `new app has an empty draft at revision 0`() {
        val id = createApp()
        call(alice, get("/api/apps/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.draft.revision").value(0))
            .andExpect(jsonPath("$.draft.nodes.length()").value(0))
            .andExpect(jsonPath("$.draft.problems.length()").value(0))
    }

    @Test
    fun `names are validated and unique per user ignoring case, but not across users`() {
        createApp(name = "Shop")
        call(alice, post("/api/apps"), mapOf("name" to "shop")).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("name_taken"))
        call(alice, post("/api/apps"), mapOf("name" to "  ")).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_name"))
        call(alice, post("/api/apps"), mapOf("name" to "x".repeat(61))).andExpect(status().isBadRequest)
        createApp(bob, "Shop") // another user may reuse it
    }

    @Test
    fun `rename works and respects uniqueness`() {
        val a = createApp(name = "a")
        createApp(name = "b")
        call(alice, patch("/api/apps/$a"), mapOf("name" to "renamed")).andExpect(status().isOk).andExpect(jsonPath("$.name").value("renamed"))
        call(alice, patch("/api/apps/$a"), mapOf("name" to "B")).andExpect(status().isConflict)
        call(alice, patch("/api/apps/$a"), mapOf("name" to "RENAMED")).andExpect(status().isOk) // same app, case change
    }

    @Test
    fun `delete removes the app and its specs`() {
        val id = createApp()
        call(alice, post("/api/apps/$id/versions"), mapOf("note" to "x")).andExpect(status().isCreated)
        call(alice, delete("/api/apps/$id")).andExpect(status().isNoContent)
        call(alice, get("/api/apps/$id")).andExpect(status().isNotFound)
        call(alice, get("/api/apps")).andExpect(jsonPath("$.length()").value(0))
        val left = em.createNativeQuery("select count(*) from app_specs where app_id = :id").setParameter("id", java.util.UUID.fromString(id)).singleResult
        assertEquals(0L, (left as Number).toLong())
    }

    @Test
    fun `other users cannot see, change or delete an app`() {
        val id = createApp(alice)
        call(bob, get("/api/apps/$id")).andExpect(status().isNotFound)
        call(bob, patch("/api/apps/$id"), mapOf("name" to "mine")).andExpect(status().isNotFound)
        call(bob, put("/api/apps/$id/draft"), draft(emptyList(), base = 0)).andExpect(status().isNotFound)
        call(bob, post("/api/apps/$id/versions")).andExpect(status().isNotFound)
        call(bob, delete("/api/apps/$id")).andExpect(status().isNotFound)
        call(bob, get("/api/apps")).andExpect(jsonPath("$.length()").value(0))
        call(alice, get("/api/apps/$id")).andExpect(status().isOk)
    }

    @Test
    fun `api is 401 when signed out`() {
        mvc.perform(get("/api/apps")).andExpect(status().isUnauthorized)
    }

    // ---- draft ----

    @Test
    fun `draft round-trips through jsonb and bumps the revision`() {
        val id = createApp()
        val env = listOf(mapOf("key" to "LOG", "value" to "info"), mapOf("key" to "DB_PASSWORD", "secret" to true))
        val nodes = listOf(
            node("api", extra = mapOf("env" to env, "minReplicas" to 1, "maxReplicas" to 4, "cpuTarget" to 70)),
            node("db", image = "postgres:16", extra = mapOf("kind" to "stateful", "volume" to mapOf("size" to "10Gi", "mountPath" to "/var/lib/postgresql/data"))),
        )
        call(alice, put("/api/apps/$id/draft"), draft(nodes, listOf(edge("e1", "api", "db")), base = 0))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(1))

        call(alice, get("/api/apps/$id"))
            .andExpect(jsonPath("$.draft.revision").value(1))
            .andExpect(jsonPath("$.draft.nodes.length()").value(2))
            .andExpect(jsonPath("$.draft.nodes[?(@.id=='api')].position.x").value(10.5))
            .andExpect(jsonPath("$.draft.nodes[?(@.id=='api')].data.env[0].key").value("LOG"))
            .andExpect(jsonPath("$.draft.nodes[?(@.id=='api')].data.env[1].secret").value(true))
            .andExpect(jsonPath("$.draft.nodes[?(@.id=='db')].data.volume.size").value("10Gi"))
            .andExpect(jsonPath("$.draft.nodes[0].selected").doesNotExist())
            .andExpect(jsonPath("$.draft.edges[0].source").value("api"))
    }

    @Test
    fun `a stale baseRevision is a 409 that reports the current revision and changes nothing`() {
        val id = createApp()
        call(alice, put("/api/apps/$id/draft"), draft(listOf(node("a")), base = 0)).andExpect(status().isOk)
        call(alice, put("/api/apps/$id/draft"), draft(listOf(node("a"), node("b")), base = 0))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("conflict"))
            .andExpect(jsonPath("$.currentRevision").value(1))
        call(alice, get("/api/apps/$id")).andExpect(jsonPath("$.draft.nodes.length()").value(1))
        call(alice, put("/api/apps/$id/draft"), mapOf("nodes" to emptyList<Any>(), "edges" to emptyList<Any>())).andExpect(status().isBadRequest)
    }

    @Test
    fun `structurally invalid graphs are rejected with paths and nothing is saved`() {
        val id = createApp()
        fun bad(nodes: List<Any>, edges: List<Any> = emptyList(), path: String) =
            call(alice, put("/api/apps/$id/draft"), draft(nodes, edges, base = 0))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error").value("invalid_graph"))
                .andExpect(jsonPath("$.errors[?(@.path=='$path')]").exists())

        bad(listOf(node("a"), node("a")), path = "nodes[1].id")
        bad(listOf(node("a")), listOf(edge("e", "a", "ghost")), path = "edges[0].target")
        bad(listOf(node("a")), listOf(edge("e", "a", "a")), path = "edges[0]")
        bad(listOf(node("a"), node("b")), listOf(edge("e1", "a", "b"), edge("e2", "a", "b")), path = "edges[1]")
        bad(listOf(node("a", extra = mapOf("kind" to "weird"))), path = "nodes[0].data.kind")
        bad(listOf(node("a", extra = mapOf("env" to listOf(mapOf("key" to "K", "value" to "leak", "secret" to true))))), path = "nodes[0].data.env[0].value")
        bad(List(101) { node("n$it") }, path = "nodes")
        call(alice, get("/api/apps/$id")).andExpect(jsonPath("$.draft.revision").value(0))
    }

    @Test
    fun `incomplete drafts are saved and reported as problems`() {
        val id = createApp()
        val nodes = listOf(
            node("a", name = "Bad Name", image = ""),
            node("b", name = "dup"), node("c", name = "dup"),
            node("d", name = "db", extra = mapOf("kind" to "stateful", "minReplicas" to 1)),
            node("e", name = "x", extra = mapOf("replicas" to 9, "minReplicas" to 1, "maxReplicas" to 4)),
        )
        val body = call(alice, put("/api/apps/$id/draft"), draft(nodes, base = 0)).andExpect(status().isOk).andReturn().response.contentAsString
        val problems = HashSet<String>()
        for (p in json.readTree(body).get("problems")) problems += p.get("nodeId").asString() + "." + p.get("field").asString()
        for (expected in listOf("a.name", "a.image", "b.name", "c.name", "d.volume", "d.minReplicas", "e.replicas")) {
            assertTrue(expected in problems, "missing $expected in $problems")
        }
    }

    @Test
    fun `a public container needs a port and a name short enough for its web address`() {
        val id = createApp()
        val nodes = listOf(
            node("a", name = "web", extra = mapOf("expose" to true, "port" to null)),
            node("b", name = "x".repeat(55), extra = mapOf("expose" to true)),
            node("c", name = "y".repeat(54), extra = mapOf("expose" to true)),
            node("d", name = "z".repeat(60)), // as long as a name may be, but not public: no address to build
        )
        val body = call(alice, put("/api/apps/$id/draft"), draft(nodes, base = 0)).andExpect(status().isOk).andReturn().response.contentAsString
        val problems = HashSet<String>()
        for (p in json.readTree(body).get("problems")) problems += p.get("nodeId").asString() + "." + p.get("field").asString()
        assertTrue("a.port" in problems, "a public container with no port: $problems")
        assertTrue("b.name" in problems, "55 characters is one too many for a public name: $problems")
        for (fine in listOf("c.name", "c.port", "d.name", "d.port", "b.port")) assertTrue(fine !in problems, "$fine should be fine: $problems")
    }

    // ---- versions ----

    @Test
    fun `save version snapshots the draft, skips duplicates, and numbers versions`() {
        val id = createApp()
        call(alice, put("/api/apps/$id/draft"), draft(listOf(node("a")), base = 0))

        call(alice, post("/api/apps/$id/versions"), mapOf("note" to "  first  "))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.created").value(true))
            .andExpect(jsonPath("$.version.revision").value(1))
            .andExpect(jsonPath("$.version.note").value("first"))
            .andExpect(jsonPath("$.version.nodeCount").value(1))

        // unchanged draft: no new version
        call(alice, post("/api/apps/$id/versions")).andExpect(status().isOk).andExpect(jsonPath("$.created").value(false)).andExpect(jsonPath("$.version.revision").value(1))

        call(alice, put("/api/apps/$id/draft"), draft(listOf(node("a"), node("b")), base = 1))
        call(alice, post("/api/apps/$id/versions")).andExpect(status().isCreated).andExpect(jsonPath("$.version.revision").value(2))

        call(alice, get("/api/apps/$id/versions"))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].revision").value(2))
            .andExpect(jsonPath("$[1].revision").value(1))
        call(alice, get("/api/apps/$id/versions/1")).andExpect(jsonPath("$.nodes.length()").value(1))
        call(alice, get("/api/apps/$id/versions/9")).andExpect(status().isNotFound)
    }

    @Test
    fun `restore copies a version into the draft and leaves versions untouched`() {
        val id = createApp()
        call(alice, put("/api/apps/$id/draft"), draft(listOf(node("a")), base = 0))
        call(alice, post("/api/apps/$id/versions")).andExpect(status().isCreated)
        call(alice, put("/api/apps/$id/draft"), draft(listOf(node("a"), node("b"), node("c")), base = 1))

        call(alice, post("/api/apps/$id/versions/1/restore"), mapOf("baseRevision" to 99)).andExpect(status().isConflict)
        call(alice, post("/api/apps/$id/versions/1/restore"), mapOf("baseRevision" to 2))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.revision").value(3))
            .andExpect(jsonPath("$.nodes.length()").value(1))
        call(alice, get("/api/apps")).andExpect(jsonPath("$[0].componentCount").value(1))
        call(alice, get("/api/apps/$id/versions/1")).andExpect(jsonPath("$.nodes.length()").value(1))
        // restored draft equals version 1, so saving again is a no-op
        call(alice, post("/api/apps/$id/versions")).andExpect(status().isOk).andExpect(jsonPath("$.created").value(false))
    }

    @Test
    fun `only the newest 50 saved versions are kept`() {
        val id = createApp()
        var base = 0L
        repeat(52) { i ->
            call(alice, put("/api/apps/$id/draft"), draft(listOf(node("a", extra = mapOf("replicas" to i + 1))), base = base)).andExpect(status().isOk)
            base++
            call(alice, post("/api/apps/$id/versions")).andExpect(status().isCreated)
        }
        call(alice, get("/api/apps/$id/versions"))
            .andExpect(jsonPath("$.length()").value(50))
            .andExpect(jsonPath("$[0].revision").value(52))
            .andExpect(jsonPath("$[49].revision").value(3))
    }
}
