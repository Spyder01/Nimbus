package com.spyder01.nimbus.backend.apps.services

import com.spyder01.nimbus.backend.apps.dto.GraphEdge
import com.spyder01.nimbus.backend.apps.dto.GraphNode

/**
 * Dependency graph helpers. An edge `source -> target` means **source needs target**, so the target
 * is deployed first. Pure functions: no I/O, easy to reason about and test.
 */
object GraphAlgorithms {
    /** One container of a deployment plan. [dependsOn] holds the node ids this one needs. */
    data class Step(val node: GraphNode, val layer: Int, val ordinal: Int, val dependsOn: List<String>)

    class CycleException(val cycles: List<List<String>>) : RuntimeException("The graph has a circular dependency")

    private fun dependencies(nodes: List<GraphNode>, edges: List<GraphEdge>): Map<String, List<String>> {
        val ids = nodes.mapTo(HashSet()) { it.id }
        return nodes.associate { n ->
            n.id to edges.filter { it.source == n.id && it.target in ids }.map { it.target }.distinct()
        }
    }

    /**
     * Every dependency cycle, as the node ids involved: the strongly connected components with more than
     * one node (Tarjan). Containers that merely depend on a cycle aren't part of it and aren't listed.
     */
    fun cycles(nodes: List<GraphNode>, edges: List<GraphEdge>): List<List<String>> {
        val deps = dependencies(nodes, edges)
        val index = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = HashSet<String>()
        val found = mutableListOf<List<String>>()
        var counter = 0

        fun visit(v: String) {
            index[v] = counter
            low[v] = counter
            counter++
            stack.addLast(v)
            onStack += v
            for (w in deps.getValue(v)) {
                if (w !in index) {
                    visit(w)
                    low[v] = minOf(low.getValue(v), low.getValue(w))
                } else if (w in onStack) {
                    low[v] = minOf(low.getValue(v), index.getValue(w))
                }
            }
            if (low[v] == index[v]) {
                val component = mutableListOf<String>()
                do {
                    val w = stack.removeLast()
                    onStack -= w
                    component += w
                } while (w != v)
                if (component.size > 1) found += component
            }
        }
        // The graph is capped at 100 containers, so recursion depth is not a concern.
        for (n in nodes) if (n.id !in index) visit(n.id)
        return found
    }

    /** A readable path around a cycle, e.g. `web -> api -> web`, for error messages. */
    fun describe(cycle: List<String>, nodes: List<GraphNode>, edges: List<GraphEdge>): String {
        val names = nodes.associate { it.id to (it.data.name.trim().ifEmpty { it.id }) }
        val inside = cycle.toSet()
        val deps = dependencies(nodes, edges).mapValues { (_, v) -> v.filter { it in inside } }
        val start = cycle.minBy { names.getValue(it) }

        // Shortest path from start back to start, staying inside the cycle.
        val parent = HashMap<String, String>()
        val queue = ArrayDeque(listOf(start))
        val seen = hashSetOf<String>()
        var last: String? = null
        search@ while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (w in deps.getValue(v)) {
                if (w == start) {
                    last = v
                    break@search
                }
                if (seen.add(w)) {
                    parent[w] = v
                    queue.addLast(w)
                }
            }
        }
        val path = mutableListOf(start)
        var cur = last
        val back = mutableListOf<String>()
        while (cur != null && cur != start) {
            back += cur
            cur = parent[cur]
        }
        path += back.reversed()
        path += start
        return path.joinToString(" → ") { names.getValue(it) }
    }

    /**
     * Orders the containers so every container comes after the ones it needs, grouped into layers: a layer's
     * containers need nothing in the same layer, so they can be deployed in parallel. Within a layer the order
     * is by name, so the plan for a given graph is always the same. Throws [CycleException] on a cycle.
     */
    fun plan(nodes: List<GraphNode>, edges: List<GraphEdge>): List<Step> {
        val deps = dependencies(nodes, edges)
        val byId = nodes.associateBy { it.id }
        val layerOf = HashMap<String, Int>()
        val steps = mutableListOf<Step>()
        var remaining = nodes.map { it.id }.toSet()

        var layer = 0
        while (remaining.isNotEmpty()) {
            val ready = remaining
                .filter { id -> deps.getValue(id).all { it in layerOf } }
                .sortedWith(compareBy({ byId.getValue(it).data.name }, { it }))
            if (ready.isEmpty()) throw CycleException(cycles(nodes, edges))
            for (id in ready) {
                layerOf[id] = layer
                steps += Step(byId.getValue(id), layer, steps.size, deps.getValue(id))
            }
            remaining = remaining - ready.toSet()
            layer++
        }
        return steps
    }
}
