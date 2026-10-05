package com.spyder01.nimbus.backend.users.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class UpdateProfileRequest(
    @field:NotBlank @field:Size(max = 100)
    val name: String,
    @field:Email @field:Size(max = 254)
    val email: String? = null,
    @field:Size(max = 2048)
    val avatarUrl: String? = null,
)
