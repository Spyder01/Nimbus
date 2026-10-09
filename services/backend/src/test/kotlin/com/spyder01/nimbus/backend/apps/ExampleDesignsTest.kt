package com.spyder01.nimbus.backend.apps

import com.spyder01.nimbus.backend.apps.dto.GraphNode
import com.spyder01.nimbus.backend.apps.services.AppYamlImporter
import com.spyder01.nimbus.backend.apps.services.GraphValidator
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The example designs in /examples are what people are told to import, so each one has to import and be ready to
 * deploy. (The tests run from services/backend, so the examples are two folders up.)
 */
class ExampleDesignsTest {
    private val importer = AppYamlImporter()
    private val validator = GraphValidator()
    private val dir = File("../../examples")

    private fun example(name: String) = File(dir, name).also { assertTrue(it.isFile, "missing example ${it.path}") }.readText()
    private fun parse(name: String) = importer.parse(example(name))
    private fun byName(nodes: List<GraphNode>) = nodes.associateBy { it.data.name }

    @Test
    fun `every example imports and has nothing left to fix before deploying`() {
        val files = dir.listFiles { f -> f.extension == "yaml" }.orEmpty().sortedBy { it.name }
        assertTrue(files.size >= 3, "expected the examples to be found in ${dir.absolutePath}")
        for (f in files) {
            val g = importer.parse(f.readText())
            validator.requireWellFormed(g.nodes, g.edges)
            assertEquals(emptyList(), validator.problems(g.nodes, g.edges).map { "${it.field}: ${it.message}" }, "${f.name} has problems")
            assertTrue(g.nodes.isNotEmpty(), "${f.name} is empty")
        }
    }

    @Test
    fun `the Directus quickstart has no credentials in it and can be deployed today`() {
        val g = parse("directus-quickstart.yaml")
        val directus = byName(g.nodes).getValue("directus").data
        assertEquals(1, g.nodes.size)
        assertTrue(directus.image.startsWith("directus/directus:"), directus.image)
        assertEquals(8055, directus.port); assertTrue(directus.expose)
        assertEquals("stateless", directus.kind); assertNull(directus.volume)
        // Nothing secret, and nothing that looks like a password or key with a value: the first visitor creates the admin.
        assertFalse(directus.env.any { it.secret == true })
        assertEquals(setOf("DB_CLIENT", "DB_FILENAME"), directus.env.map { it.key }.toSet())
    }

    @Test
    fun `the full Directus design keeps its passwords secret and deploys the database first`() {
        val g = parse("directus.yaml")
        val nodes = byName(g.nodes)
        val db = nodes.getValue("db"); val directus = nodes.getValue("directus")

        assertEquals("stateful", db.data.kind); assertNotNull(db.data.volume)
        assertEquals("stateful", directus.data.kind); assertEquals("/directus/uploads", directus.data.volume?.mountPath)
        assertTrue(directus.data.expose); assertEquals(8055, directus.data.port)

        // "directus needs db": the arrow runs from directus to db, so db is deployed first.
        assertEquals(listOf(directus.id to db.id), g.edges.map { it.source to it.target })

        // Every password is a secret (key kept, value never stored), and the plain settings are not.
        fun secrets(n: GraphNode) = n.data.env.filter { it.secret == true }.map { it.key }.toSet()
        assertEquals(setOf("POSTGRES_PASSWORD"), secrets(db))
        assertEquals(setOf("DB_PASSWORD", "SECRET", "ADMIN_PASSWORD"), secrets(directus))
        for (n in nodes.values) assertTrue(n.data.env.filter { it.secret == true }.all { it.value == null }, "a secret has a value")
        assertEquals("db", directus.data.env.first { it.key == "DB_HOST" }.value)
        assertEquals("pg", directus.data.env.first { it.key == "DB_CLIENT" }.value)
    }
}
