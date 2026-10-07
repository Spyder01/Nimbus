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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AppImportApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val em: EntityManager,
    @Autowired private val json: JsonMapper,
) {
    private lateinit var alice: User

    // Created in @BeforeEach, which runs inside the test's transaction, so these rows are rolled back with it.
    // (As property initializers they ran before the transaction began and stayed in the database for good.)
    @BeforeEach
    fun createUsers() {
        alice = users.saveAndFlush(User(name = "alice"))
    }
    private val uiExport = AppImportApiTest::class.java.getResource("/apps/ui-export.yaml")!!.readText()

    private fun importApp(name: String?, yaml: String?, user: User = alice): ResultActions {
        em.flush(); em.clear()
        return mvc.perform(
            post("/api/apps/import").with(oauth2Login().attributes { it["userId"] = user.id.toString() }).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(mapOf("name" to name, "yaml" to yaml))),
        )
    }

    private fun asAlice(req: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder): ResultActions {
        em.flush(); em.clear()
        return mvc.perform(req.with(oauth2Login().attributes { it["userId"] = alice.id.toString() }).with(csrf()))
    }

    @Test
    fun `importing creates a new app with the name from the form and the graph from the file`() {
        val id = json.readTree(
            importApp("My Shop", uiExport).andExpect(status().isCreated)
                .andExpect(jsonPath("$.name").value("My Shop")) // not the 'app:' key in the file
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.draft.revision").value(0))
                .andExpect(jsonPath("$.draft.nodes.length()").value(4))
                .andExpect(jsonPath("$.draft.edges.length()").value(3))
                .andExpect(jsonPath("$.draft.problems.length()").value(0)) // complete, so it can be deployed straight away
                .andReturn().response.contentAsString,
        ).get("id").asString()

        asAlice(get("/api/apps")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].componentCount").value(4))
        asAlice(get("/api/apps/$id"))
            .andExpect(jsonPath("$.draft.nodes[?(@.data.name=='db')].data.volume.size").value("10Gi"))
            .andExpect(jsonPath("$.draft.nodes[?(@.data.name=='db')].data.env[0].secret").value(true))
            .andExpect(jsonPath("$.draft.nodes[?(@.data.name=='web')].position.x").value(0.0))
    }

    @Test
    fun `an imported app is an ordinary app that can be edited`() {
        val id = json.readTree(importApp("shop", uiExport).andReturn().response.contentAsString).get("id").asString()
        asAlice(
            put("/api/apps/$id/draft").contentType(MediaType.APPLICATION_JSON)
                .content("""{"nodes":[],"edges":[],"baseRevision":0}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.revision").value(1))
    }

    @Test
    fun `incomplete containers import and are flagged for the canvas`() {
        importApp("half", "services:\n  web: {}\n").andExpect(status().isCreated)
            .andExpect(jsonPath("$.draft.problems[0].field").value("image"))
    }

    @Test
    fun `a bad file is reported with line numbers and nothing is created`() {
        importApp("shop", "services:\n  web:\n    image: nginx\n    replica: 3\n")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_yaml"))
            .andExpect(jsonPath("$.errors[0].line").value(4))
            .andExpect(jsonPath("$.errors[0].message").value("Unknown key 'replica' in 'web' (expected: image, port, expose, kind, replicas, autoscale, volume, env, needs)"))
        asAlice(get("/api/apps")).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `missing or empty yaml is a 400, not a server error`() {
        importApp("shop", null).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_yaml"))
        importApp("shop", "").andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_yaml"))
    }

    @Test
    fun `the new app's name follows the same rules as creating one`() {
        importApp("shop", uiExport).andExpect(status().isCreated)
        importApp("SHOP", uiExport).andExpect(status().isConflict).andExpect(jsonPath("$.error").value("name_taken"))
        importApp("  ", uiExport).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_name"))
        importApp(null, uiExport).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("invalid_name"))
        asAlice(get("/api/apps")).andExpect(jsonPath("$.length()").value(1))
    }

    @Test
    fun `a file over the size limit is refused`() {
        importApp("big", "services:\n  web:\n    image: " + "x".repeat(300 * 1024)).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].message").value("The file is too large (the limit is 256 KB)"))
    }

    @Test
    fun `importing needs a signed-in user`() {
        mvc.perform(
            post("/api/apps/import").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"x","yaml":"services: {}"}"""),
        ).andExpect(status().isUnauthorized)
    }
}
