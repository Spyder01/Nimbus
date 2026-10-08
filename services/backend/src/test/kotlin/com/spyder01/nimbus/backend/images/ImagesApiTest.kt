package com.spyder01.nimbus.backend.images

import com.spyder01.nimbus.backend.images.dto.CatalogImageDto
import com.spyder01.nimbus.backend.images.dto.ImageCatalogDto
import com.spyder01.nimbus.backend.images.dto.ImageEnvDto
import com.spyder01.nimbus.backend.images.dto.ImageVolumeDto
import com.spyder01.nimbus.backend.images.services.ImageCatalogService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class ImagesApiTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val service: ImageCatalogService,
    @Autowired private val json: JsonMapper,
) {
    private val user = oauth2Login().attributes { it["userId"] = UUID.randomUUID().toString() }

    @Test
    fun `the catalog needs a signed-in user`() {
        mvc.perform(get("/api/images")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `a signed-in user gets the whole catalog, which may be cached briefly`() {
        val result = mvc.perform(get("/api/images").with(user))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=300")))
            .andExpect(jsonPath("$.categories[0]").value("Web & proxies"))
            .andExpect(jsonPath("$.images.length()").value(service.catalog.images.size))
            .andReturn()
        val images = json.readTree(result.response.contentAsString).get("images")
        fun entry(image: String) = images.first { it.get("image").asString() == image }

        val postgres = entry("postgres")
        assertEquals("/var/lib/postgresql/data", postgres.get("volume").get("mountPath").asString())
        val password = postgres.get("env").first { it.get("key").asString() == "POSTGRES_PASSWORD" }
        assertTrue(password.get("secret").asBoolean())
        assertFalse(password.has("value"), "a secret has no value")

        // Optional parts are left out rather than sent as null.
        val nginx = entry("nginx")
        assertFalse(nginx.has("volume")); assertFalse(nginx.has("env"))
    }

    @Test
    fun `the shipped catalog is valid and sizeable`() {
        assertEquals(emptyList(), ImageCatalogService.validate(service.catalog))
        assertTrue(service.catalog.images.size >= 50, "expected an extensive catalog, got ${service.catalog.images.size}")
        assertTrue(service.catalog.images.any { it.popular == true }, "something must be marked popular for the empty search")
    }

    // ---- the checks that guard the file ----

    private fun image(
        id: String = "x", image: String = "x", category: String = "A", kind: String = "stateless",
        tags: List<String> = listOf("1"), defaultTag: String = "1", port: Int? = 80,
        volume: ImageVolumeDto? = null, env: List<ImageEnvDto>? = null,
    ) = CatalogImageDto(id, image, "X", category, "d", port, kind, false, tags, defaultTag, "x", emptyList(), volume, env)

    private fun problems(vararg images: CatalogImageDto, categories: List<String> = listOf("A")) =
        ImageCatalogService.validate(ImageCatalogDto(categories, images.toList()))

    private fun assertProblem(part: String, found: List<String>) =
        assertTrue(found.any { part in it }, "expected a problem mentioning \"$part\", got $found")

    @Test
    fun `a good entry has no problems`() {
        assertEquals(emptyList(), problems(image()))
        assertEquals(
            emptyList(),
            problems(image(kind = "stateful", volume = ImageVolumeDto("/data", "10Gi"), env = listOf(ImageEnvDto("PW", secret = true)))),
        )
    }

    @Test
    fun `bad entries are reported`() {
        assertProblem("duplicate id", problems(image(), image(image = "y")))
        assertProblem("duplicate image", problems(image(), image(id = "y")))
        assertProblem("unknown category", problems(image(category = "B")))
        assertProblem("kind must be", problems(image(kind = "weird")))
        assertProblem("defaultTag", problems(image(defaultTag = "2")))
        assertProblem("at least one tag", problems(image(tags = emptyList(), defaultTag = "1")))
        assertProblem("out of range", problems(image(port = 70000)))
        assertProblem("needs a volume", problems(image(kind = "stateful")))
        assertProblem("can't have a volume", problems(image(volume = ImageVolumeDto("/d", "1Gi"))))
        assertProblem("absolute", problems(image(kind = "stateful", volume = ImageVolumeDto("data", "1Gi"))))
        assertProblem("not like 10Gi", problems(image(kind = "stateful", volume = ImageVolumeDto("/data", "10GB"))))
        assertProblem("must not have a value", problems(image(env = listOf(ImageEnvDto("PW", "oops", secret = true)))))
        assertProblem("duplicate environment variable", problems(image(env = listOf(ImageEnvDto("A", "1"), ImageEnvDto("A", "2")))))
    }
}
