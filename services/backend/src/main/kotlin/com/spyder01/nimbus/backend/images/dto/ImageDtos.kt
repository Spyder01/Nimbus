package com.spyder01.nimbus.backend.images.dto

import com.fasterxml.jackson.annotation.JsonInclude

/** An environment variable a catalog image needs. A secret has no value: the user enters it when deploying. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ImageEnvDto(
    val key: String,
    val value: String? = null,
    val secret: Boolean? = null,
)

data class ImageVolumeDto(
    val mountPath: String,
    /** A Kubernetes quantity, e.g. "10Gi". */
    val size: String,
)

/** One curated image, with what is needed to run it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CatalogImageDto(
    val id: String,
    /** The image name without a tag, e.g. `postgres` or `ghcr.io/open-webui/open-webui`. */
    val image: String,
    val name: String,
    val category: String,
    val description: String,
    val port: Int?,
    /** "stateless" or "stateful". */
    val kind: String,
    /** Whether it is reachable from the internet by default. */
    val expose: Boolean,
    val tags: List<String>,
    val defaultTag: String,
    /** Suggested container name. */
    val base: String,
    /** Extra words the image can be found by. */
    val keywords: List<String>,
    val volume: ImageVolumeDto? = null,
    val env: List<ImageEnvDto>? = null,
    /** Shown first when nothing has been searched for yet. */
    val popular: Boolean? = null,
)

data class ImageCatalogDto(
    /** In the order they should be listed. */
    val categories: List<String>,
    val images: List<CatalogImageDto>,
)
