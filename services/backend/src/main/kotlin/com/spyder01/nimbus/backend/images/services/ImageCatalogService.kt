package com.spyder01.nimbus.backend.images.services

import com.spyder01.nimbus.backend.images.dto.ImageCatalogDto
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

/**
 * The curated list of container images users can pick from (`images/catalog.json` on the classpath). It is read and
 * checked once at startup, so a mistake in the file stops the application from starting instead of reaching users.
 *
 * Only images that run with their default command belong here: a container has no command or arguments of its own.
 */
@Service
class ImageCatalogService(mapper: JsonMapper) {
    val catalog: ImageCatalogDto =
        ClassPathResource(RESOURCE).inputStream.use { mapper.readValue(it, ImageCatalogDto::class.java) }.also { c ->
            val problems = validate(c)
            check(problems.isEmpty()) { "$RESOURCE is invalid:\n - " + problems.joinToString("\n - ") }
        }

    companion object {
        const val RESOURCE = "images/catalog.json"

        /** Everything wrong with a catalog, in words; empty when it is fine. */
        fun validate(c: ImageCatalogDto): List<String> {
            val problems = mutableListOf<String>()
            fun dupes(label: String, values: List<String>) =
                values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { problems += "duplicate $label \"$it\"" }

            dupes("category", c.categories)
            dupes("id", c.images.map { it.id })
            dupes("image", c.images.map { it.image })
            for (i in c.images) {
                val at = "\"${i.image}\""
                if (i.category !in c.categories) problems += "$at: unknown category \"${i.category}\""
                if (i.kind != "stateless" && i.kind != "stateful") problems += "$at: kind must be stateless or stateful, not \"${i.kind}\""
                if (i.tags.isEmpty()) problems += "$at: needs at least one tag"
                if (i.defaultTag !in i.tags) problems += "$at: defaultTag \"${i.defaultTag}\" is not one of its tags"
                dupes("tag of $at", i.tags)
                if (i.port != null && i.port !in 1..65535) problems += "$at: port ${i.port} is out of range"
                if (i.kind == "stateful" && i.volume == null) problems += "$at: a stateful image needs a volume"
                if (i.kind == "stateless" && i.volume != null) problems += "$at: a stateless image can't have a volume"
                i.volume?.let { v ->
                    if (!v.mountPath.startsWith("/")) problems += "$at: volume path must be absolute"
                    if (!Regex("[1-9][0-9]*(Mi|Gi|Ti)").matches(v.size)) problems += "$at: volume size \"${v.size}\" is not like 10Gi"
                }
                for (e in i.env.orEmpty()) {
                    if (e.key.isBlank()) problems += "$at: an environment variable has no name"
                    if (e.secret == true && e.value != null) problems += "$at: secret ${e.key} must not have a value"
                }
                dupes("environment variable of $at", i.env.orEmpty().map { it.key })
            }
            return problems
        }
    }
}
