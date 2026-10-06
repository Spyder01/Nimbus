package com.spyder01.nimbus.backend.apps.services

import com.spyder01.nimbus.backend.apps.dto.GraphEdge
import com.spyder01.nimbus.backend.apps.dto.GraphNode
import org.springframework.stereotype.Component
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest

/**
 * Stable fingerprint of a graph: the same design hashes the same regardless of the order nodes and
 * edges arrive in. Positions are included, so a layout-only change is a different version.
 */
@Component
class GraphHasher(mapper: JsonMapper) {
    private val canonical = mapper.rebuild().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build()

    fun hash(nodes: List<GraphNode>, edges: List<GraphEdge>): String {
        val json = canonical.writeValueAsString(
            mapOf("nodes" to nodes.sortedBy { it.id }, "edges" to edges.sortedBy { it.id }),
        )
        return MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
