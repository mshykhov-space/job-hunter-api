package com.mshykhov.jobhunter.api.rest.job

import com.mshykhov.jobhunter.support.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant

@TestPropertySource(properties = ["jobhunter.oidc.enabled=true"])
class JobAuthorizationIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `personal job write permission cannot mutate the shared vacancy collection`() {
        for (endpoint in listOf("/jobs/ingest", "/jobs/check")) {
            mockMvc.post(endpoint) {
                with(authentication(token("write:jobs")))
                contentType = APPLICATION_JSON
                content = "[]"
            }.andExpect { status { isForbidden() } }
        }
        mockMvc.post("/jobs/rematch") {
            with(authentication(token("write:jobs")))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `machine permissions allow ingestion without granting personal or operator access`() {
        for (endpoint in listOf("/jobs/ingest", "/jobs/check")) {
            mockMvc.post(endpoint) {
                with(authentication(token("write:jobs", "read:criteria")))
                contentType = APPLICATION_JSON
                content = "[]"
            }.andExpect { status { isOk() } }
        }
        mockMvc.post("/jobs/rematch") {
            with(authentication(token("write:jobs", "read:criteria")))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `global rematching requires the dedicated operator permission`() {
        mockMvc.post("/jobs/rematch") {
            with(authentication(token("manage:jobs")))
        }.andExpect { status { isOk() } }
    }

    private fun token(vararg permissions: String): JwtAuthenticationToken {
        val jwt =
            Jwt.withTokenValue("authorization-test")
                .header("alg", "none")
                .subject("test-user@example.test")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build()
        return JwtAuthenticationToken(jwt, permissions.map { SimpleGrantedAuthority("SCOPE_$it") })
    }
}
