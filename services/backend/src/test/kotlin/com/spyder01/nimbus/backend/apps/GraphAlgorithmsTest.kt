package com.spyder01.nimbus.backend.apps

import com.spyder01.nimbus.backend.apps.dto.ContainerData
import com.spyder01.nimbus.backend.apps.dto.GraphEdge
import com.spyder01.nimbus.backend.apps.dto.GraphNode
import com.spyder01.nimbus.backend.apps.services.GraphAlgorithms
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// An edge "a" to "b" means a NEEDS b, so b is deployed first.
class GraphAlgorithmsTest {
    private fun node(name: String) = GraphNode(id = name, data = ContainerData(name = name, image = "img"))
    private fun nodes(vararg names: String) = names.map(::node)
    private fun edges(vararg pairs: Pair<String, String>) = pairs.mapIndexed { i, (s, t) -> GraphEdge("e$i", s, t) }

    private fun order(nodes: List<GraphNode>, edges: List<GraphEdge>) = GraphAlgorithms.plan(nodes, edges).map { it.node.id }
    private fun layers(nodes: List<GraphNode>, edges: List<GraphEdge>) =
        GraphAlgorithms.plan(nodes, edges).groupBy({ it.layer }, { it.node.id }).toSortedMap().values.toList()

    @Test
    fun `what a container needs is deployed before it`() {
        assertEquals(listOf("db", "api", "web"), order(nodes("web", "api", "db"), edges("web" to "api", "api" to "db")))
    }

    @Test
    fun `independent containers share a layer and are ordered by name`() {
        val n = nodes("web", "api", "db", "cache")
        val e = edges("web" to "api", "api" to "db", "api" to "cache")
        assertEquals(listOf(listOf("cache", "db"), listOf("api"), listOf("web")), layers(n, e))
        assertEquals(listOf("cache", "db", "api", "web"), order(n, e))
    }

    @Test
    fun `a diamond waits for both branches`() {
        val n = nodes("top", "left", "right", "bottom")
        val e = edges("top" to "left", "top" to "right", "left" to "bottom", "right" to "bottom")
        assertEquals(listOf(listOf("bottom"), listOf("left", "right"), listOf("top")), layers(n, e))
    }

    @Test
    fun `unconnected containers are all in the first layer`() {
        assertEquals(listOf(listOf("a", "b", "c")), layers(nodes("c", "a", "b"), emptyList()))
    }

    @Test
    fun `steps list exactly what each container needs`() {
        val steps = GraphAlgorithms.plan(nodes("web", "api", "db"), edges("web" to "api", "web" to "db", "api" to "db"))
        assertEquals(emptyList(), steps.single { it.node.id == "db" }.dependsOn)
        assertEquals(listOf("db"), steps.single { it.node.id == "api" }.dependsOn)
        assertEquals(setOf("api", "db"), steps.single { it.node.id == "web" }.dependsOn.toSet())
        assertEquals(listOf(0, 1, 2), steps.map { it.ordinal })
    }

    @Test
    fun `the plan for a graph is the same whatever order it is given in`() {
        val e = edges("web" to "api", "api" to "db")
        assertEquals(order(nodes("web", "api", "db"), e), order(nodes("db", "web", "api"), e.reversed()))
    }

    @Test
    fun `no cycles in an ordinary graph`() {
        assertTrue(GraphAlgorithms.cycles(nodes("a", "b", "c"), edges("a" to "b", "b" to "c", "a" to "c")).isEmpty())
    }

    @Test
    fun `a two-container loop is a cycle`() {
        val cycles = GraphAlgorithms.cycles(nodes("a", "b"), edges("a" to "b", "b" to "a"))
        assertEquals(listOf(setOf("a", "b")), cycles.map { it.toSet() })
    }

    @Test
    fun `a longer loop is one cycle, and containers that only depend on it are not part of it`() {
        val n = nodes("a", "b", "c", "user", "leaf")
        val e = edges("a" to "b", "b" to "c", "c" to "a", "user" to "a", "c" to "leaf")
        val cycles = GraphAlgorithms.cycles(n, e)
        assertEquals(listOf(setOf("a", "b", "c")), cycles.map { it.toSet() })
    }

    @Test
    fun `separate loops are reported separately`() {
        val n = nodes("a", "b", "c", "d")
        val cycles = GraphAlgorithms.cycles(n, edges("a" to "b", "b" to "a", "c" to "d", "d" to "c"))
        assertEquals(setOf(setOf("a", "b"), setOf("c", "d")), cycles.map { it.toSet() }.toSet())
    }

    @Test
    fun `planning a cyclic graph fails and says which containers`() {
        val ex = assertFailsWith<GraphAlgorithms.CycleException> {
            GraphAlgorithms.plan(nodes("a", "b", "c"), edges("a" to "b", "b" to "a", "c" to "a"))
        }
        assertEquals(listOf(setOf("a", "b")), ex.cycles.map { it.toSet() })
    }

    @Test
    fun `a cycle is described as a path that returns to its start`() {
        val n = nodes("web", "api", "worker")
        val e = edges("web" to "api", "api" to "worker", "worker" to "web")
        val text = GraphAlgorithms.describe(GraphAlgorithms.cycles(n, e).single(), n, e)
        assertEquals("api → worker → web → api", text)
    }

    @Test
    fun `a two-container cycle reads as there and back`() {
        val n = nodes("web", "api")
        val e = edges("web" to "api", "api" to "web")
        assertEquals("api → web → api", GraphAlgorithms.describe(GraphAlgorithms.cycles(n, e).single(), n, e))
    }
}
