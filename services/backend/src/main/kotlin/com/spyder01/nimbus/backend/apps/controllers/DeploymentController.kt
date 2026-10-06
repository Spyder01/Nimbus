package com.spyder01.nimbus.backend.apps.controllers

import com.spyder01.nimbus.backend.apps.dto.DeploymentDto
import com.spyder01.nimbus.backend.apps.dto.DeploymentList
import com.spyder01.nimbus.backend.apps.services.DeploymentService
import com.spyder01.nimbus.backend.shared.userId
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/apps/{appId}/deployments")
class DeploymentController(
    private val service: DeploymentService,
) {
    /** Validates the graph and queues a deployment of it. 422 with `problems` if the graph isn't complete. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun deploy(@AuthenticationPrincipal user: OAuth2User, @PathVariable appId: UUID): DeploymentDto =
        service.deploy(user.userId(), appId)

    /** Newest first, with the app's state: the UI polls this while a deployment is active. */
    @GetMapping
    fun list(@AuthenticationPrincipal user: OAuth2User, @PathVariable appId: UUID): DeploymentList =
        service.list(user.userId(), appId)

    @GetMapping("/{deploymentId}")
    fun get(@AuthenticationPrincipal user: OAuth2User, @PathVariable appId: UUID, @PathVariable deploymentId: UUID): DeploymentDto =
        service.get(user.userId(), appId, deploymentId)

    @PostMapping("/{deploymentId}/cancel")
    fun cancel(@AuthenticationPrincipal user: OAuth2User, @PathVariable appId: UUID, @PathVariable deploymentId: UUID): DeploymentDto =
        service.cancel(user.userId(), appId, deploymentId)
}
