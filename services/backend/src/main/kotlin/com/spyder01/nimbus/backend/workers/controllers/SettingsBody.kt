package com.spyder01.nimbus.backend.workers.controllers

import com.spyder01.nimbus.backend.shared.ApiException
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode

private val FIELDS = setOf("parallelJobs", "leaseSeconds")

/**
 * The body of a settings update: both fields, each a whole number or null (inherit), and nothing else, so a misspelt
 * field can't silently clear a setting. Returns (parallelJobs, leaseSeconds).
 */
internal fun parseSettingsBody(body: JsonNode): Pair<Int?, Int?> {
    if (!body.isObject || body.propertyNames().any { it !in FIELDS }) throw invalid()
    fun whole(field: String): Int? {
        val value = body.get(field) ?: throw invalid()
        return when {
            value.isNull -> null
            value.isIntegralNumber && value.canConvertToInt() -> value.intValue()
            else -> throw ApiException(HttpStatus.BAD_REQUEST, "invalid_body", "$field must be a whole number or null")
        }
    }
    return whole("parallelJobs") to whole("leaseSeconds")
}

private fun invalid() = ApiException(
    HttpStatus.BAD_REQUEST, "invalid_body",
    "The body must be a JSON object with exactly ${FIELDS.joinToString(" and ") { "'$it'" }} (each a whole number, or null to inherit)",
)
