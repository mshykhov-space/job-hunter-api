package com.mshykhov.jobhunter.infrastructure.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunter.api.rest.exception.ErrorResponse
import com.mshykhov.jobhunter.infrastructure.automation.AutomationProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.AuthenticationManagerResolver
import org.springframework.security.authentication.ProviderManager
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.client.RestTemplate

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(OidcProperties::class, AutomationProperties::class)
class SecurityConfig(private val oidcProperties: OidcProperties, private val objectMapper: ObjectMapper) {
    @Bean
    @ConditionalOnProperty(
        prefix = "jobhunter.oidc",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/actuator/**")
                    .permitAll()
                    .requestMatchers("/public/**")
                    .permitAll()
                    .requestMatchers("/swagger-ui/**", "/api-docs/**")
                    .permitAll()
                    .requestMatchers("/mcp", "/mcp/**")
                    .hasAuthority("SCOPE_report:automation-health")
                    .requestMatchers(HttpMethod.GET, "/automation/status")
                    .hasAuthority("SCOPE_read:automation")
                    .requestMatchers(HttpMethod.PUT, "/automation/delegation")
                    .hasAuthority("SCOPE_write:automation")
                    .requestMatchers(HttpMethod.DELETE, "/automation/delegation")
                    .hasAuthority("SCOPE_write:automation")
                    .requestMatchers(HttpMethod.GET, "/automation/workflows/**")
                    .hasAuthority("SCOPE_read:automation")
                    .requestMatchers(HttpMethod.POST, "/automation/workflows/**")
                    .hasAuthority("SCOPE_write:automation")
                    .requestMatchers("/automation/runner/**")
                    .hasAuthority("SCOPE_report:automation-health")
                    .requestMatchers("/automation/materials/**")
                    .hasAuthority("SCOPE_report:automation-health")
                    .requestMatchers("/scraping/**")
                    .authenticated()
                    .requestMatchers("/jobs/**", "/materials/**", "/criteria/**", "/preferences/**", "/proxies/**", "/settings/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll()
            }.oauth2ResourceServer { oauth2 ->
                oauth2.authenticationManagerResolver(issuerAuthenticationManagerResolver())
                oauth2.authenticationEntryPoint { _, response, _ ->
                    response.status = HttpStatus.UNAUTHORIZED.value()
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    objectMapper.writeValue(response.outputStream, ErrorResponse("Unauthorized", "UNAUTHORIZED"))
                }
                oauth2.accessDeniedHandler { _, response, _ ->
                    response.status = HttpStatus.FORBIDDEN.value()
                    response.contentType = MediaType.APPLICATION_JSON_VALUE
                    objectMapper.writeValue(response.outputStream, ErrorResponse("Access denied", "FORBIDDEN"))
                }
            }.csrf { it.disable() }

        return http.build()
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "jobhunter.oidc",
        name = ["enabled"],
        havingValue = "false",
    )
    fun disabledSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { }
            .addFilterBefore(DevAuthenticationFilter(), UsernamePasswordAuthenticationFilter::class.java)
            .authorizeHttpRequests { auth ->
                auth.anyRequest().permitAll()
            }.csrf { it.disable() }

        return http.build()
    }

    // Authentik issues tokens per application (SPA and M2M providers have their
    // own slug issuers), so each configured issuer gets its own decoder while
    // sharing the audience check and authorities mapping.
    private fun issuerAuthenticationManagerResolver(): JwtIssuerAuthenticationManagerResolver {
        val managers: Map<String, AuthenticationManager> =
            oidcProperties.issuers.associateWith { issuer ->
                val provider =
                    JwtAuthenticationProvider(jwtDecoder(issuer)).apply {
                        setJwtAuthenticationConverter(jwtAuthenticationConverter())
                    }
                ProviderManager(provider)
            }
        return JwtIssuerAuthenticationManagerResolver(AuthenticationManagerResolver { issuer -> managers[issuer] })
    }

    private fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val authoritiesConverter =
            JwtGrantedAuthoritiesConverter().apply {
                setAuthoritiesClaimName("permissions")
                setAuthorityPrefix("SCOPE_")
            }
        return JwtAuthenticationConverter().apply {
            setJwtGrantedAuthoritiesConverter(authoritiesConverter)
        }
    }

    private fun jwtDecoder(issuer: String): JwtDecoder {
        val requestFactory =
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(JWKS_TIMEOUT_MILLIS)
                setReadTimeout(JWKS_TIMEOUT_MILLIS)
            }
        val jwtDecoder =
            NimbusJwtDecoder
                .withIssuerLocation(issuer)
                .restOperations(RestTemplate(requestFactory))
                .build()

        val audienceValidator = AudienceValidator(oidcProperties.audience)
        val issuerValidator = JwtValidators.createDefaultWithIssuer(issuer)

        jwtDecoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(issuerValidator, audienceValidator),
        )

        return jwtDecoder
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "jobhunter.oidc",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun authServiceUnavailableFilterRegistration(): FilterRegistrationBean<AuthServiceUnavailableFilter> =
        FilterRegistrationBean(AuthServiceUnavailableFilter(objectMapper)).apply {
            order = Ordered.HIGHEST_PRECEDENCE
        }

    private companion object {
        const val JWKS_TIMEOUT_MILLIS = 5000
    }
}

@Configuration
@ConditionalOnProperty(
    prefix = "jobhunter.oidc",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableMethodSecurity
class MethodSecurityConfig
