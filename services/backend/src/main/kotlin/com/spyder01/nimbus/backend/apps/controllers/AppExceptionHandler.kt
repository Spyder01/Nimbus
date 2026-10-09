package com.spyder01.nimbus.backend.apps.controllers

import com.spyder01.nimbus.backend.shared.ApiException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Turns API errors into `{error, message, ...}` bodies. Scoped to the apps, workers and users controllers. */
@RestControllerAdvice(
    basePackages = [
        "com.spyder01.nimbus.backend.apps.controllers",
        "com.spyder01.nimbus.backend.workers.controllers",
        "com.spyder01.nimbus.backend.users.controllers",
    ],
)
class AppExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(e.status).body(mapOf("error" to e.code, "message" to e.message) + e.details)

    // Two writers raced past the revision check, or a unique index (name, version number) rejected a duplicate.
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class, DataIntegrityViolationException::class)
    fun raced(): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(mapOf("error" to "conflict", "message" to "That changed at the same time elsewhere; reload and retry"))

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun unreadable(): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.badRequest().body(mapOf("error" to "invalid_body", "message" to "The request couldn't be read"))
}
