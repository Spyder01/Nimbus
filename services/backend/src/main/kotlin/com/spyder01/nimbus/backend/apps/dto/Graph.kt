package com.spyder01.nimbus.backend.apps.dto

/**
 * The design of an app: `(nodes, edges)`, shaped like React Flow's graph so the canvas can load and
 * save it unchanged. Unknown fields the canvas adds (selected, measured, ...) are ignored on input.
 */
data class GraphNode(
    val id: String,
    val type: String = "container",
    val position: Position = Position(),
    val data: ContainerData = ContainerData(),
)

data class Position(val x: Double = 0.0, val y: Double = 0.0)

data class ContainerData(
    val name: String = "",
    val image: String = "",
    val port: Int? = null,
    val expose: Boolean = false,
    /** "stateless" or "stateful" (stateful keeps a volume). */
    val kind: String = "stateless",
    val replicas: Int = 1,
    val minReplicas: Int? = null,
    val maxReplicas: Int? = null,
    val cpuTarget: Int? = null,
    val env: List<EnvVar> = emptyList(),
    val volume: Volume? = null,
)

/** Secret values are never part of the graph: a secret entry carries only its key. */
data class EnvVar(val key: String = "", val value: String? = null, val secret: Boolean = false)

data class Volume(val size: String = "", val mountPath: String = "")

data class GraphEdge(
    val id: String,
    val source: String,
    val target: String,
    /** Reserved (e.g. target port later). */
    val data: Map<String, Any?> = emptyMap(),
)

/** A completeness issue that doesn't stop a draft being saved but will block deploying later. */
data class Problem(val nodeId: String?, val field: String?, val message: String)
