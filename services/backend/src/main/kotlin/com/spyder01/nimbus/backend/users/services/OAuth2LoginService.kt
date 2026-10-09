package com.spyder01.nimbus.backend.users.services

import com.spyder01.nimbus.backend.users.entities.Provider
import com.spyder01.nimbus.backend.users.entities.User
import com.spyder01.nimbus.backend.users.entities.UserIdentity
import com.spyder01.nimbus.backend.users.repositories.UserIdentityRepository
import com.spyder01.nimbus.backend.users.repositories.UserRepository
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Runs after a successful OAuth2 login: fetches the provider profile, finds or creates the matching
 * [User] / [UserIdentity], and returns a principal whose authorities come from the user's role.
 */
@Service
class OAuth2LoginService(
    private val users: UserRepository,
    private val identities: UserIdentityRepository,
) : OAuth2UserService<OAuth2UserRequest, OAuth2User> {
    private val delegate = DefaultOAuth2UserService()

    private data class Profile(
        val providerUserId: String,
        val login: String?,
        val email: String?,
        val name: String?,
        val avatarUrl: String?,
    )

    @Transactional
    override fun loadUser(request: OAuth2UserRequest): OAuth2User {
        val oauthUser = delegate.loadUser(request)
        val provider = Provider.fromRegistrationId(request.clientRegistration.registrationId)
        val profile = profileOf(provider, oauthUser.attributes)

        val identity = identities.findByProviderAndProviderUserId(provider, profile.providerUserId)
        val user =
            if (identity != null) {
                identity.login = profile.login
                identity.email = profile.email
                identity.user.also {
                    // once the user has saved their own profile, provider data must not overwrite it
                    if (!it.profileUpdated) {
                        it.name = profile.name
                        it.email = profile.email
                        it.avatarUrl = profile.avatarUrl
                    }
                }
            } else {
                val newUser = users.save(User(name = profile.name, email = profile.email, avatarUrl = profile.avatarUrl))
                identities.save(UserIdentity(newUser, provider, profile.providerUserId, profile.login, profile.email))
                newUser
            }

        val nameAttribute =
            requireNotNull(request.clientRegistration.providerDetails.userInfoEndpoint.userNameAttributeName) {
                "No user-name-attribute configured for ${provider.name}"
            }
        return DefaultOAuth2User(
            user.role.authorities(),
            oauthUser.attributes + ("userId" to user.id.toString()),
            nameAttribute,
        )
    }

    private fun profileOf(provider: Provider, attrs: Map<String, Any?>): Profile =
        when (provider) {
            Provider.GITHUB ->
                Profile(
                    providerUserId = attrs["id"].toString(),
                    login = attrs["login"] as String?,
                    email = attrs["email"] as String?,
                    name = attrs["name"] as String?,
                    avatarUrl = attrs["avatar_url"] as String?,
                )
            Provider.GOOGLE ->
                Profile(
                    providerUserId = attrs["sub"].toString(),
                    login = null,
                    email = attrs["email"] as String?,
                    name = attrs["name"] as String?,
                    avatarUrl = attrs["picture"] as String?,
                )
        }
}
