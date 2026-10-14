package com.mshykhov.jobhunter.infrastructure.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Instant

class DevAuthenticationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (SecurityContextHolder.getContext().authentication == null) {
            SecurityContextHolder.getContext().authentication = createDevAuthentication()
        }
        filterChain.doFilter(request, response)
    }

    private fun createDevAuthentication(): JwtAuthenticationToken {
        val jwt =
            Jwt
                .withTokenValue("dev-token")
                .header("alg", "none")
                .issuer("http://localhost/dev")
                .subject(DEV_USER_SUB)
                .claim("scope", DEV_SCOPES.joinToString(" "))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build()

        val authorities = DEV_SCOPES.map { SimpleGrantedAuthority("SCOPE_$it") }

        return JwtAuthenticationToken(jwt, authorities)
    }

    companion object {
        const val DEV_USER_SUB = "local-dev-user"

        private val DEV_SCOPES =
            listOf(
                "read:jobs",
                "write:jobs",
                "manage:jobs",
                "read:criteria",
                "read:preferences",
                "write:preferences",
                "read:proxies",
                "read:settings",
                "write:settings",
                "report:automation-health",
                "read:automation",
                "write:automation",
            )
    }
}
