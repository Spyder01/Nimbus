package com.spyder01.nimbus.backend.users

import com.spyder01.nimbus.backend.users.entities.User
import com.spyder01.nimbus.backend.users.repositories.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

// @Transactional: MockMvc runs on the test thread, so rows written here are rolled back.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProfileFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
) {
    private fun login(user: User) = oauth2Login().attributes { it["userId"] = user.id.toString() }
    private val json = MediaType.APPLICATION_JSON

    @Test
    fun `api is 401 not a redirect when signed out`() {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `new user is flagged, saving the profile clears the flag`() {
        val user = users.saveAndFlush(User(name = "gh-name"))

        mvc.perform(get("/api/me").with(login(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.profileUpdated").value(false))

        mvc.perform(
            put("/api/me").with(login(user)).with(csrf()).contentType(json)
                .content("""{"name":" Ada Lovelace ","email":"ada@example.com"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Ada Lovelace"))
            .andExpect(jsonPath("$.profileUpdated").value(true))

        mvc.perform(get("/api/me").with(login(user))).andExpect(jsonPath("$.profileUpdated").value(true))
    }

    @Test
    fun `blank name or bad email is rejected and does not flip the flag`() {
        val user = users.saveAndFlush(User())
        mvc.perform(
            put("/api/me").with(login(user)).with(csrf()).contentType(json).content("""{"name":" ","email":"nope"}"""),
        ).andExpect(status().isBadRequest)
        mvc.perform(get("/api/me").with(login(user))).andExpect(jsonPath("$.profileUpdated").value(false))
    }
}
