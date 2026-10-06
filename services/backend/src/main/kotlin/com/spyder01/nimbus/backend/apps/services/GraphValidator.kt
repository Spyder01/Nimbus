package com.spyder01.nimbus.backend.apps.services

import com.spyder01.nimbus.backend.apps.dto.GraphEdge
import com.spyder01.nimbus.backend.apps.dto.GraphNode
import com.spyder01.nimbus.backend.apps.dto.Problem
import com.spyder01.nimbus.backend.shared.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/**
 * Two levels of checking:
 *  - [requireWellFormed]: structure and limits. A violation is a 400 and nothing is saved.
 *  - [problems]: completeness (blank image, bad name, ...). A draft is allowed to be incomplete, so
 *    these are returned to the UI as warnings and will block deploying later.
 */
@Component
class GraphValidator {
    companion object {
        const val MAX_NODES = 100
        const val MAX_EDGES = 500
        private const val MAX_ENV = 100
        private const val MAX_ERRORS = 20
        private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val KINDS = setOf("stateless", "stateful")
        private val DNS_LABEL = Regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")
        private val ENV_KEY = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val QUANTITY = Regex("^[1-9][0-9]*(Mi|Gi|Ti)$")
    }

    fun requireWellFormed(nodes: List<GraphNode>, edges: List<GraphEdge>) {
        val errors = mutableListOf<Map<String, String>>()

        fun err(path: String, message: String) {
            if (errors.size < MAX_ERRORS) errors += mapOf("path" to path, "message" to message)
        }

        if (nodes.size > MAX_NODES) err("nodes", "At most $MAX_NODES containers per app")
        if (edges.size > MAX_EDGES) err("edges", "At most $MAX_EDGES connections per app")

        val ids = HashSet<String>()
        nodes.forEachIndexed { i, n ->
            val p = "nodes[$i]"
            if (!ID.matches(n.id)) err("$p.id", "Id must be 1-64 letters, digits, '_' or '-'")
            else if (!ids.add(n.id)) err("$p.id", "Duplicate node id '${n.id}'")
            if (n.type != "container") err("$p.type", "Unsupported node type '${n.type}'")
            if (!n.position.x.isFinite() || !n.position.y.isFinite()) err("$p.position", "Position must be finite")
            val d = n.data
            if (d.kind !in KINDS) err("$p.data.kind", "Kind must be 'stateless' or 'stateful'")
            if (d.name.length > 253) err("$p.data.name", "Name is too long")
            if (d.image.length > 255) err("$p.data.image", "Image is too long")
            listOf("replicas" to d.replicas, "minReplicas" to d.minReplicas, "maxReplicas" to d.maxReplicas)
                .forEach { (f, v) -> if (v != null && v !in 0..1000) err("$p.data.$f", "Must be between 0 and 1000") }
            if (d.port != null && d.port !in 0..65535) err("$p.data.port", "Must be between 0 and 65535")
            if (d.cpuTarget != null && d.cpuTarget !in 0..1000) err("$p.data.cpuTarget", "Out of range")
            if (d.env.size > MAX_ENV) err("$p.data.env", "At most $MAX_ENV variables per container")
            d.env.forEachIndexed { j, e ->
                if (e.key.length > 255) err("$p.data.env[$j].key", "Key is too long")
                if ((e.value?.length ?: 0) > 4096) err("$p.data.env[$j].value", "Value is too long")
                if (e.secret && e.value != null) err("$p.data.env[$j].value", "Secret values are not stored in the design")
            }
            d.volume?.let {
                if (it.size.length > 32) err("$p.data.volume.size", "Too long")
                if (it.mountPath.length > 255) err("$p.data.volume.mountPath", "Too long")
            }
        }

        val edgeIds = HashSet<String>()
        val pairs = HashSet<Pair<String, String>>()
        edges.forEachIndexed { i, e ->
            val p = "edges[$i]"
            if (!ID.matches(e.id)) err("$p.id", "Id must be 1-64 letters, digits, '_' or '-'")
            else if (!edgeIds.add(e.id)) err("$p.id", "Duplicate edge id '${e.id}'")
            if (e.source !in ids) err("$p.source", "Unknown node '${e.source}'")
            if (e.target !in ids) err("$p.target", "Unknown node '${e.target}'")
            if (e.source == e.target) err("$p", "A container can't connect to itself")
            else if (!pairs.add(e.source to e.target)) err("$p", "Duplicate connection")
        }

        if (errors.isNotEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "invalid_graph", "The graph is not valid", mapOf("errors" to errors))
        }
    }

    fun problems(nodes: List<GraphNode>, edges: List<GraphEdge>): List<Problem> {
        val out = mutableListOf<Problem>()
        val nameCount = nodes.groupingBy { it.data.name.trim() }.eachCount()

        for (n in nodes) {
            val d = n.data

            fun problem(field: String, message: String) {
                out += Problem(n.id, field, message)
            }

            val name = d.name.trim()
            when {
                name.isEmpty() -> problem("name", "Give this container a name")
                name.length > 63 || !DNS_LABEL.matches(name) ->
                    problem("name", "Use lowercase letters, digits and '-' (max 63), starting and ending with a letter or digit")
                (nameCount[name] ?: 0) > 1 -> problem("name", "Another container already uses this name")
            }
            if (d.image.isBlank()) problem("image", "Enter an image, e.g. nginx:1.27")
            if (d.port != null && d.port !in 1..65535) problem("port", "Port must be between 1 and 65535")
            if (d.replicas < 1) problem("replicas", "Run at least one replica")

            val autoscale = d.minReplicas != null || d.maxReplicas != null || d.cpuTarget != null
            if (d.minReplicas != null && d.maxReplicas != null && d.minReplicas > d.maxReplicas)
                problem("minReplicas", "Min can't be above max")
            if (d.minReplicas != null && d.replicas < d.minReplicas) problem("replicas", "Replicas are below the minimum")
            if (d.maxReplicas != null && d.replicas > d.maxReplicas) problem("replicas", "Replicas are above the maximum")
            if (d.cpuTarget != null && d.cpuTarget !in 1..100) problem("cpuTarget", "CPU target must be between 1 and 100")

            if (d.kind == "stateful") {
                if (autoscale) problem("minReplicas", "Stateful containers keep a fixed replica count")
                val v = d.volume
                if (v == null) problem("volume", "Stateful containers need a volume")
                else {
                    if (!QUANTITY.matches(v.size)) problem("volume.size", "Use a size like 10Gi")
                    if (!v.mountPath.startsWith("/")) problem("volume.mountPath", "Mount path must start with '/'")
                }
            }

            val keys = HashSet<String>()
            d.env.forEachIndexed { j, e ->
                if (!ENV_KEY.matches(e.key)) problem("env[$j].key", "Variable names use letters, digits and '_'")
                else if (!keys.add(e.key)) problem("env[$j].key", "Duplicate variable '${e.key}'")
            }
        }

        // A container can't be deployed after something that needs it: flag every container in a cycle.
        for (cycle in GraphAlgorithms.cycles(nodes, edges)) {
            val message = "Circular dependency: ${GraphAlgorithms.describe(cycle, nodes, edges)}"
            for (id in cycle) out += Problem(id, "connections", message)
        }
        return out
    }
}
