package com.spyder01.nimbus.backend.images.controllers

import com.spyder01.nimbus.backend.images.dto.ImageCatalogDto
import com.spyder01.nimbus.backend.images.services.ImageCatalogService
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

@RestController
@RequestMapping("/api/images")
class ImageController(private val service: ImageCatalogService) {
    /**
     * The whole curated catalog. It is small and only changes with a release, so the client takes it in one request and
     * searches it locally; browsers may keep it for a few minutes.
     */
    @GetMapping
    fun catalog(): ResponseEntity<ImageCatalogDto> =
        ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate()).body(service.catalog)
}
