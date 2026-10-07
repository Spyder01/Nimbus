package com.spyder01.nimbus.backend.apps

import com.spyder01.nimbus.backend.apps.dto.EnvVar
import com.spyder01.nimbus.backend.apps.services.AppYamlImporter
import com.spyder01.nimbus.backend.shared.ApiException
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppYamlImporterTest {
    private val importer = AppYamlImporter()

    /** The exact text the canvas' Export button produced for a sample app (see the frontend's yaml.ts). */
    private val uiExport = AppYamlImporterTest::class.java.getResource("/apps/ui-export.yaml")!!.readText()

    private fun parse(yaml: String) = importer.parse(yaml)

    @Suppress("UNCHECKED_CAST")
    private fun problems(yaml: String): List<Map<String, Any?>> {
        val ex = assertFailsWith<ApiException> { parse(yaml) }
        assertEquals("invalid_yaml", ex.code)
        return ex.details["errors"] as List<Map<String, Any?>>
    }

    private fun messages(yaml: String) = problems(yaml).map { it["message"] as String }

    // ---- what the UI exports ----

    @Test
    fun `reads what the canvas exports`() {
        val g = parse(uiExport)
        val byName = g.nodes.associateBy { it.data.name }
        assertEquals(setOf("web", "api", "db", "cache"), byName.keys)

        byName.getValue("web").data.let {
            assertEquals("nginx:1.27", it.image); assertEquals(80, it.port); assertTrue(it.expose)
            assertEquals("stateless", it.kind); assertEquals(2, it.replicas)
        }
        byName.getValue("api").data.let {
            assertEquals("ghcr.io/acme/api:2.1", it.image); assertEquals(3, it.replicas)
            assertEquals(2, it.minReplicas); assertEquals(8, it.maxReplicas); assertEquals(70, it.cpuTarget)
            // quoted look-alikes stay text, and an empty value stays empty
            assertEquals(
                listOf(EnvVar("LOG_LEVEL", "info"), EnvVar("GREETING", "hello: world"), EnvVar("PORT", "8080"), EnvVar("EMPTY", "")),
                it.env,
            )
        }
        byName.getValue("db").data.let {
            assertEquals("stateful", it.kind)
            assertEquals("10Gi", it.volume?.size); assertEquals("/var/lib/postgresql/data", it.volume?.mountPath)
            assertEquals(listOf(EnvVar("POSTGRES_PASSWORD", null, secret = true)), it.env) // a secret keeps its key, never a value
        }
        assertEquals("1Gi", byName.getValue("cache").data.volume?.size)
    }

    @Test
    fun `needs become edges from the container to what it needs`() {
        val g = parse(uiExport)
        val name = g.nodes.associate { it.id to it.data.name }
        assertEquals(
            setOf("web" to "api", "api" to "db", "api" to "cache"),
            g.edges.map { name.getValue(it.source) to name.getValue(it.target) }.toSet(),
        )
        assertEquals(g.nodes.size, g.nodes.map { it.id }.toSet().size, "node ids are unique")
        assertEquals(g.edges.size, g.edges.map { it.id }.toSet().size, "edge ids are unique")
    }

    @Test
    fun `containers are laid out in columns with what they need to the right`() {
        val at = parse(uiExport).nodes.associate { it.data.name to it.position }
        assertEquals(0.0, at.getValue("web").x)
        assertEquals(320.0, at.getValue("api").x)
        assertEquals(640.0, at.getValue("db").x)
        assertEquals(640.0, at.getValue("cache").x)
        assertTrue(at.getValue("cache").y != at.getValue("db").y, "containers in one column don't overlap")
    }

    @Test
    fun `a circular dependency still imports, with a grid layout, so it can be fixed on the canvas`() {
        val g = parse("services:\n  a:\n    image: x\n    needs: [b]\n  b:\n    image: y\n    needs: [a]\n")
        assertEquals(2, g.nodes.size); assertEquals(2, g.edges.size)
        assertEquals(setOf(0.0, 320.0), g.nodes.map { it.position.x }.toSet())
    }

    // ---- shape and defaults ----

    @Test
    fun `only the image is needed and the rest default`() {
        val d = parse("services:\n  web:\n    image: nginx\n").nodes.single().data
        assertEquals("web", d.name); assertEquals("nginx", d.image)
        assertEquals("stateless", d.kind); assertEquals(1, d.replicas)
        assertNull(d.port); assertTrue(!d.expose); assertTrue(d.env.isEmpty()); assertNull(d.volume); assertNull(d.minReplicas)
    }

    @Test
    fun `the app key is ignored and no services is an empty app`() {
        assertEquals(0, parse("app: anything\nservices: {}\n").nodes.size)
        assertEquals(0, parse("services:\n").nodes.size)
    }

    @Test
    fun `a service with no body is imported as an incomplete container for the canvas to flag`() {
        val d = parse("services:\n  web: {}\n").nodes.single().data
        assertEquals("", d.image)
    }

    // ---- mistakes get a line number and a plain message ----

    @Test
    fun `an empty file or the wrong top-level shape is explained`() {
        assertEquals(listOf("The file is empty"), messages("   \n"))
        assertTrue(messages("- a\n- b\n").single().contains("mapping with a 'services' key"))
        assertTrue(messages("app: x\n").single().contains("Missing 'services'"))
    }

    @Test
    fun `a misspelt key points at its line`() {
        val p = problems("services:\n  web:\n    image: nginx\n    replica: 3\n").single()
        assertEquals(4, p["line"])
        assertTrue((p["message"] as String).startsWith("Unknown key 'replica' in 'web'"))
        assertTrue(messages("servces: {}\n").first().contains("Unknown key 'servces'"))
    }

    @Test
    fun `wrong value types say what was expected`() {
        val m = messages(
            """
            services:
              web:
                image: nginx
                replicas: many
                port: 80.5
                expose: yes
                kind: weird
                autoscale: 5
                volume: [a]
                env: [A]
                needs: api
            """.trimIndent(),
        )
        assertTrue(m.any { it.contains("replicas must be a whole number, not 'many'") })
        assertTrue(m.any { it.contains("port must be a whole number") })
        assertTrue(m.any { it.contains("expose must be true or false, not 'yes'") })
        assertTrue(m.any { it.contains("kind must be 'stateless' or 'stateful'") })
        assertTrue(m.any { it.contains("autoscale must look like") })
        assertTrue(m.any { it.contains("volume must look like") })
        assertTrue(m.any { it.contains("env must be a mapping") })
        assertTrue(m.any { it.contains("needs must be a list") })
    }

    @Test
    fun `needs must name services that exist and not themselves`() {
        val m = messages("services:\n  web:\n    image: x\n    needs: [web, ghost]\n")
        assertTrue(m.any { it.contains("'web' can't need itself") })
        assertTrue(m.any { it.contains("'ghost', which isn't one of the services") })
    }

    @Test
    fun `duplicate keys are rejected instead of silently keeping the last one`() {
        assertTrue(messages("services:\n  web:\n    image: a\n  web:\n    image: b\n").any { it.contains("Duplicate key 'web'") })
        assertTrue(messages("services:\n  web:\n    image: a\n    image: b\n").any { it.contains("Duplicate key 'image'") })
    }

    @Test
    fun `a YAML syntax error reports its line`() {
        val p = problems("services:\n  web:\n    image: [unclosed\n").single()
        assertTrue(p["line"] as Int >= 3, "line ${p["line"]}")
        assertEquals("invalid_yaml", assertFailsWith<ApiException> { parse("services:\n\tweb: {}\n") }.code)
    }

    @Test
    fun `only one document is allowed`() {
        assertTrue(messages("services: {}\n---\nservices: {}\n").single().contains("Only one YAML document"))
    }

    @Test
    fun `every problem in the file is reported together, up to a limit`() {
        val many = (1..30).joinToString("\n") { "  s$it:\n    nope: 1" }
        val list = problems("services:\n$many\n")
        assertEquals(20, list.size)
        assertEquals(3, problems("services:\n  a:\n    x: 1\n    y: 2\n    z: 3\n").size)
    }

    // ---- hostile input ----

    @Test
    fun `global tags, the way unsafe loaders get tricked into running code, are refused`() {
        val classic = "services:\n  web: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://127.0.0.1:1/x.jar\"]]]]\n"
        assertTrue(messages(classic).single().contains("Global tag is not allowed"))
        assertTrue(messages("services:\n  web: !!python/object:os.system\n    image: nginx\n").single().contains("Global tag is not allowed"))
    }

    @Test
    fun `a local tag is ignored and its value read as plain text`() {
        val d = parse("services:\n  web: !custom\n    image: !whatever nginx\n").nodes.single().data
        assertEquals("nginx", d.image) // nothing is constructed from a tag
    }

    @Test
    fun `an alias bomb is refused quickly`() {
        val bomb = buildString {
            append("services:\n  web:\n    image: x\n")
            append("a0: &a0 [x,x,x,x,x,x,x,x,x]\n")
            for (i in 1..9) append("a$i: &a$i [${(1..9).joinToString(",") { "*a${i - 1}" }}]\n")
        }
        var thrown: ApiException? = null
        val ms = measureTimeMillis { thrown = assertFailsWith<ApiException> { parse(bomb) } }
        assertEquals("invalid_yaml", thrown!!.code)
        assertTrue(ms < 2000, "took ${ms}ms")
    }

    @Test
    fun `huge or deeply nested files are refused`() {
        val big = "services:\n  web:\n    image: " + "x".repeat(AppYamlImporter.MAX_CHARS)
        assertTrue(messages(big).single().contains("too large"))

        val deep = "services:\n  web:\n    image: x\n    needs: " + "[".repeat(100) + "]".repeat(100) + "\n"
        assertEquals("invalid_yaml", assertFailsWith<ApiException> { parse(deep) }.code)
    }

    @Test
    fun `merge keys aren't expanded`() {
        assertTrue(messages("base: &b {image: x}\nservices:\n  web:\n    <<: *b\n").any { it.contains("Unknown key") })
    }
}
