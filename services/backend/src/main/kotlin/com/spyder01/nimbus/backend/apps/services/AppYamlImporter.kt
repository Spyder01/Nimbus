package com.spyder01.nimbus.backend.apps.services

import com.spyder01.nimbus.backend.apps.dto.ContainerData
import com.spyder01.nimbus.backend.apps.dto.EnvVar
import com.spyder01.nimbus.backend.apps.dto.GraphEdge
import com.spyder01.nimbus.backend.apps.dto.GraphNode
import com.spyder01.nimbus.backend.apps.dto.Position
import com.spyder01.nimbus.backend.apps.dto.Volume
import com.spyder01.nimbus.backend.shared.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.StringReader
import java.util.UUID

/**
 * Turns the YAML an app is exported as into a graph. The format (also what the canvas' Export button writes):
 *
 *     app: shop                  # ignored on import: the new app's name comes from the import form
 *     services:
 *       web:
 *         image: nginx:1.27
 *         port: 80
 *         expose: true
 *         kind: stateless        # or stateful
 *         replicas: 2
 *         autoscale: { min: 1, max: 8, cpu: 70 }
 *         volume: { size: 10Gi, mount: /data }
 *         env:
 *           LOG_LEVEL: info
 *           DB_PASSWORD: "<secret>"   # a secret: the key is kept, never a value
 *         needs: [api]           # web needs api, so api is deployed first
 *
 * Layout isn't part of the format; containers are placed automatically (what a container needs is to its right).
 *
 * The input is untrusted, so the YAML is only *composed* into a plain node tree: no object is ever constructed
 * from a tag, so `!!` tags can't instantiate anything. Size, alias and nesting limits stop alias bombs.
 */
@Component
class AppYamlImporter {
    companion object {
        const val MAX_CHARS = 256 * 1024
        private const val MAX_PROBLEMS = 20
        private val TOP_KEYS = setOf("app", "services")
        private val SERVICE_KEYS = setOf("image", "port", "expose", "kind", "replicas", "autoscale", "volume", "env", "needs")
        private const val SECRET = "<secret>"
        private const val COLUMN = 320.0
        private const val ROW = 160.0
    }

    class Parsed(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

    fun parse(text: String): Parsed {
        if (text.length > MAX_CHARS) throw invalid(listOf(problem(null, "The file is too large (the limit is ${MAX_CHARS / 1024} KB)")))
        val root = compose(text) ?: throw invalid(listOf(problem(null, "The file is empty")))
        val problems = Problems()

        val top = root as? MappingNode
        if (top == null) {
            problems.add(root, "The file must be a YAML mapping with a 'services' key")
            throw invalid(problems.list)
        }
        val keys = entries(top, TOP_KEYS, "the top level", problems)
        val services = keys["services"]
        if (services == null) problems.add(top, "Missing 'services'")

        val nodes = mutableListOf<GraphNode>()
        val needs = mutableListOf<Triple<String, Node, String>>() // service name, the `needs` entry, needed name
        val ids = HashMap<String, String>()

        if (services is MappingNode) {
            for ((name, valueNode) in entries(services, null, "'services'", problems)) {
                val svc = valueNode as? MappingNode
                if (svc == null) {
                    problems.add(valueNode, "'$name' must be a mapping (image, port, ...)")
                    continue
                }
                ids[name] = "n" + UUID.randomUUID().toString().replace("-", "").take(10)
                nodes += GraphNode(id = ids.getValue(name), data = service(name, svc, needs, problems))
            }
        } else if (services != null && !(services is ScalarNode && services.value.isEmpty())) {
            problems.add(services, "'services' must be a mapping of container names")
        }

        val edges = mutableListOf<GraphEdge>()
        val seen = HashSet<Pair<String, String>>()
        for ((from, at, to) in needs) {
            val target = ids[to]
            when {
                target == null -> problems.add(at, "'$from' needs '$to', which isn't one of the services")
                to == from -> problems.add(at, "'$from' can't need itself")
                seen.add(from to to) -> edges += GraphEdge("e" + UUID.randomUUID().toString().replace("-", "").take(10), ids.getValue(from), target)
            }
        }

        if (problems.list.isNotEmpty()) throw invalid(problems.list)
        return Parsed(layout(nodes, edges), edges)
    }

    // ---- one service ----

    private fun service(name: String, node: MappingNode, needs: MutableList<Triple<String, Node, String>>, p: Problems): ContainerData {
        val f = entries(node, SERVICE_KEYS, "'$name'", p)
        var data = ContainerData(name = name)

        f["image"]?.let { n -> text(n, "image", p)?.let { data = data.copy(image = it) } }
        f["port"]?.let { n -> whole(n, "port", p)?.let { data = data.copy(port = it) } }
        f["expose"]?.let { n -> flag(n, "expose", p)?.let { data = data.copy(expose = it) } }
        f["replicas"]?.let { n -> whole(n, "replicas", p)?.let { data = data.copy(replicas = it) } }
        f["kind"]?.let { n ->
            when (val k = text(n, "kind", p)) {
                null -> {}
                "stateless", "stateful" -> data = data.copy(kind = k)
                else -> p.add(n, "kind must be 'stateless' or 'stateful', not '$k'")
            }
        }

        f["autoscale"]?.let { n ->
            val m = n as? MappingNode ?: return@let p.add(n, "autoscale must look like { min: 1, max: 8, cpu: 70 }")
            val a = entries(m, setOf("min", "max", "cpu"), "autoscale", p)
            data = data.copy(
                minReplicas = a["min"]?.let { whole(it, "autoscale.min", p) },
                maxReplicas = a["max"]?.let { whole(it, "autoscale.max", p) },
                cpuTarget = a["cpu"]?.let { whole(it, "autoscale.cpu", p) },
            )
        }

        f["volume"]?.let { n ->
            val m = n as? MappingNode ?: return@let p.add(n, "volume must look like { size: 10Gi, mount: /data }")
            val v = entries(m, setOf("size", "mount"), "volume", p)
            data = data.copy(volume = Volume(size = v["size"]?.let { text(it, "volume.size", p) } ?: "", mountPath = v["mount"]?.let { text(it, "volume.mount", p) } ?: ""))
        }

        f["env"]?.let { n ->
            val m = n as? MappingNode ?: return@let p.add(n, "env must be a mapping of NAME: value")
            data = data.copy(
                env = entries(m, null, "env", p).mapNotNull { (key, v) ->
                    val value = text(v, "env.$key", p) ?: return@mapNotNull null
                    if (value == SECRET) EnvVar(key = key, value = null, secret = true) else EnvVar(key = key, value = value)
                },
            )
        }

        f["needs"]?.let { n ->
            val seq = n as? SequenceNode ?: return@let p.add(n, "needs must be a list, like [api, db]")
            for (item in seq.value) text(item, "needs", p)?.let { needs += Triple(name, item, it) }
        }
        return data
    }

    // ---- reading nodes ----

    /** The key/value pairs of a mapping, in file order. Reports duplicate keys, and keys not in [allowed] if given. */
    private fun entries(m: MappingNode, allowed: Set<String>?, where: String, p: Problems): LinkedHashMap<String, Node> {
        val out = LinkedHashMap<String, Node>()
        for (t in m.value) {
            val keyNode = t.keyNode
            val key = (keyNode as? ScalarNode)?.value
            when {
                key == null -> p.add(keyNode, "Keys in $where must be plain text")
                key in out -> p.add(keyNode, "Duplicate key '$key' in $where")
                allowed != null && key !in allowed -> p.add(keyNode, "Unknown key '$key' in $where (expected: ${allowed.joinToString(", ")})")
                else -> out[key] = t.valueNode
            }
        }
        return out
    }

    /** Scalars are read as their text; nothing is converted by tag. An empty value is an empty string. */
    private fun text(n: Node, what: String, p: Problems): String? =
        (n as? ScalarNode)?.value ?: run { p.add(n, "$what must be a single value"); null }

    private fun whole(n: Node, what: String, p: Problems): Int? {
        val raw = text(n, what, p) ?: return null
        return raw.takeIf { Regex("^[0-9]{1,9}$").matches(it) }?.toInt() ?: run { p.add(n, "$what must be a whole number, not '$raw'"); null }
    }

    private fun flag(n: Node, what: String, p: Problems): Boolean? =
        when (val raw = text(n, what, p)) {
            null -> null
            "true" -> true
            "false" -> false
            else -> { p.add(n, "$what must be true or false, not '$raw'"); null }
        }

    // ---- YAML and errors ----

    private fun compose(text: String): Node? =
        try {
            val options = LoaderOptions().apply {
                maxAliasesForCollections = 10
                codePointLimit = MAX_CHARS
                nestingDepthLimit = 20
            }
            val docs = Yaml(options).composeAll(StringReader(text)).iterator()
            val first = if (docs.hasNext()) docs.next() else null
            if (first != null && docs.hasNext()) {
                throw invalid(listOf(problem(docs.next().startMark.line + 1, "Only one YAML document is allowed (there is a second '---')")))
            }
            first
        } catch (e: MarkedYAMLException) {
            throw invalid(listOf(problem(e.problemMark?.line?.plus(1), e.problem ?: "Invalid YAML")))
        } catch (e: YAMLException) {
            throw invalid(listOf(problem(null, e.message ?: "Invalid YAML")))
        }

    private class Problems {
        val list = mutableListOf<Map<String, Any?>>()

        fun add(node: Node?, message: String) {
            if (list.size < MAX_PROBLEMS) list += problem(node?.startMark?.line?.plus(1), message)
        }
    }

    private fun invalid(problems: List<Map<String, Any?>>) = ApiException(
        HttpStatus.BAD_REQUEST, "invalid_yaml",
        if (problems.size == 1) "The YAML isn't valid: ${problems[0]["message"]}" else "The YAML has ${problems.size} problems",
        mapOf("errors" to problems),
    )

    // ---- layout ----

    /** What a container needs sits to its right, in columns by depth; a graph with a cycle can't be layered, so it gets a grid. */
    private fun layout(nodes: List<GraphNode>, edges: List<GraphEdge>): List<GraphNode> {
        val steps = try {
            GraphAlgorithms.plan(nodes, edges)
        } catch (_: GraphAlgorithms.CycleException) {
            return nodes.mapIndexed { i, n -> n.copy(position = Position((i % 4) * COLUMN, (i / 4) * ROW)) }
        }
        val deepest = steps.maxOfOrNull { it.layer } ?: 0
        val rowInLayer = HashMap<Int, Int>()
        val at = steps.associate { s ->
            val row = rowInLayer.merge(s.layer, 1, Int::plus)!! - 1
            s.node.id to Position((deepest - s.layer) * COLUMN, row * ROW)
        }
        return nodes.map { it.copy(position = at.getValue(it.id)) }
    }
}

private fun problem(line: Int?, message: String): Map<String, Any?> = mapOf("line" to line, "message" to message)
