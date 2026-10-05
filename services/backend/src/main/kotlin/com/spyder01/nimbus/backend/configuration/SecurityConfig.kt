package com.spyder01.nimbus.backend.configuration

import com.spyder01.nimbus.backend.users.services.OAuth2LoginService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    fun filterChain(
        http: HttpSecurity,
        oauth2LoginService: OAuth2LoginService,
    ): SecurityFilterChain =
        http
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health/**").permitAll()
                    .requestMatchers("/actuator/**", "/api/**").authenticated()
                    // Everything else a browser can GET is the embedded UI (static files + SPA routes);
                    // the SPA does its own signed-in/onboarding redirects.
                    .requestMatchers(HttpMethod.GET).permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2Login {
                it.userInfoEndpoint { userInfo -> userInfo.userService(oauth2LoginService) }
                    // Relative, so it lands on the UI whether it is served by Vite (dev) or embedded (prod).
                    .failureUrl("/?error=login_failed")
            }
            // API clients get a plain 401 instead of being redirected to the provider.
            .exceptionHandling {
                it.defaultAuthenticationEntryPointFor(
                    HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
                )
            }
            // The SPA reads the XSRF-TOKEN cookie and echoes it in X-XSRF-TOKEN on state-changing requests.
            .csrf {
                it.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    // A null attribute name opts out of deferred tokens so the XSRF-TOKEN cookie is always issued;
                    // otherwise the SPA has no token to send on PUT/POST.
                    .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler().apply { setCsrfRequestAttributeName(null) })
            }
            .logout { it.logoutSuccessHandler(HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)) }
            .httpBasic(Customizer.withDefaults())
            .build()
}
