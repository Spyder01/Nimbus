package com.spyder01.nimbus.backend.users.entities

enum class Provider {
    GITHUB,
    GOOGLE,
    ;

    companion object {
        // OAuth registration ids in application.yaml are lowercase ("github")
        fun fromRegistrationId(id: String): Provider = valueOf(id.uppercase())
    }
}
