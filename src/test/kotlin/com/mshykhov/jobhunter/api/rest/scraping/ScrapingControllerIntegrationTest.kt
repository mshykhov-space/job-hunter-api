package com.mshykhov.jobhunter.api.rest.scraping

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunter.application.job.Category
import com.mshykhov.jobhunter.application.preference.SearchPreferences
import com.mshykhov.jobhunter.application.preference.UserPreferenceEntity
import com.mshykhov.jobhunter.application.preference.UserPreferenceFacade
import com.mshykhov.jobhunter.application.user.UserFacade
import com.mshykhov.jobhunter.support.AbstractIntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID

@TestPropertySource(
    properties = [
        "jobhunter.scraping.enabled-sources=linkedin",
        "jobhunter.scraping.max-batch-request-size=1KB",
        "jobhunter.oidc.enabled=true",
    ],
)
class ScrapingControllerIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var userFacade: UserFacade

    @Autowired
    lateinit var preferenceFacade: UserPreferenceFacade

    @BeforeEach
    fun resetState() {
        jdbcTemplate.update("DELETE FROM scraping_batches")
        jdbcTemplate.update("DELETE FROM scraping_runs")
        jdbcTemplate.update(
            """
            UPDATE scraping_sources
            SET next_run_at = now() - interval '1 second', last_success_at = NULL,
                last_error_at = NULL, last_error_code = NULL, runs_started = 0,
                runs_succeeded = 0, runs_failed = 0, fetched_count = 0, accepted_count = 0
            """.trimIndent(),
        )
        jdbcTemplate.update("DELETE FROM user_preferences")
        val user = userFacade.findOrCreate("scraping-http-${UUID.randomUUID()}")
        preferenceFacade.save(
            UserPreferenceEntity(
                user = user,
                search = SearchPreferences(categories = setOf(Category("kotlin")), locations = listOf("Remote")),
            ),
        )
    }

    @Test
    fun `claim heartbeat complete and status follow the worker contract`() {
        val claimResult =
            mockMvc
                .post("/scraping/sources/linkedin/claim") {
                    with(authentication(token("write:jobs")))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"workerId":"worker-1"}"""
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.source") { value("linkedin") }
                    jsonPath("$.criteria.categories[0]") { value("kotlin") }
                    jsonPath("$.criteria.locations[0]") { value("Remote") }
                    jsonPath("$.checkpoint") { isMap() }
                    jsonPath("$.since") { isNotEmpty() }
                }.andReturn()
        val claim = objectMapper.readTree(claimResult.response.contentAsString)
        val runId = claim["runId"].asText()
        val leaseToken = claim["leaseToken"].asText()

        mockMvc.post("/scraping/runs/$runId/heartbeat") {
            with(authentication(token("write:jobs")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"leaseToken":"$leaseToken"}"""
        }.andExpect { status { isNoContent() } }

        mockMvc.post("/scraping/runs/$runId/complete") {
            with(authentication(token("write:jobs")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"leaseToken":"$leaseToken"}"""
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/scraping/status") {
            with(authentication(token("read:jobs")))
        }.andExpect {
            status { isOk() }
            jsonPath("$.sources.length()") { value(8) }
            jsonPath("$.sources[?(@.source == 'linkedin')].status") { value("SUCCEEDED") }
            jsonPath("$..leaseToken") { doesNotExist() }
        }
    }

    @Test
    fun `scopes distinguish worker mutations from status reads`() {
        mockMvc.post("/scraping/sources/linkedin/claim") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"workerId":"worker"}"""
        }.andExpect { status { isUnauthorized() } }

        mockMvc.post("/scraping/sources/linkedin/claim") {
            with(authentication(token("read:jobs")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"workerId":"worker"}"""
        }.andExpect { status { isForbidden() } }

        mockMvc.get("/scraping/status") {
            with(authentication(token("write:jobs")))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `invalid source payload and oversized batch return bad request`() {
        mockMvc.post("/scraping/sources/unknown/claim") {
            with(authentication(token("write:jobs")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"workerId":"worker"}"""
        }.andExpect { status { isBadRequest() } }

        val claim =
            mockMvc
                .post("/scraping/sources/linkedin/claim") {
                    with(authentication(token("write:jobs")))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"workerId":"worker"}"""
                }.andReturn()
        val claimJson = objectMapper.readTree(claim.response.contentAsString)
        val jobs = List(251) { emptyMap<String, String>() }
        val body =
            mapOf(
                "leaseToken" to claimJson["leaseToken"].asText(),
                "batchId" to UUID.randomUUID(),
                "jobs" to jobs,
                "checkpoint" to emptyMap<String, String>(),
                "fetchedCount" to 251,
            )
        mockMvc.post("/scraping/runs/${claimJson["runId"].asText()}/batches") {
            with(authentication(token("write:jobs")))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(body)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `batch request body is capped by bytes read from the stream`() {
        mockMvc.post("/scraping/runs/${UUID.randomUUID()}/batches") {
            with(authentication(token("write:jobs")))
            contentType = MediaType.APPLICATION_JSON
            content = "x".repeat(1_025)
            header("Transfer-Encoding", "chunked")
        }.andExpect {
            status { isPayloadTooLarge() }
            jsonPath("$.code") { value("PAYLOAD_TOO_LARGE") }
        }
    }

    @Test
    fun `invalid job fields are rejected before persistence and lease conflicts are structured`() {
        val claimResult =
            mockMvc
                .post("/scraping/sources/linkedin/claim") {
                    with(authentication(token("write:jobs")))
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"workerId":"worker"}"""
                }.andReturn()
        val claim = objectMapper.readTree(claimResult.response.contentAsString)
        val runId = claim["runId"].asText()
        val leaseToken = claim["leaseToken"].asText()
        val invalidJob =
            mapOf(
                "title" to "x".repeat(501),
                "url" to "https://example.test/job",
                "source" to "linkedin",
                "category" to "kotlin",
            )
        val invalidBatch =
            mapOf(
                "leaseToken" to leaseToken,
                "batchId" to UUID.randomUUID(),
                "jobs" to listOf(invalidJob),
                "checkpoint" to emptyMap<String, String>(),
                "fetchedCount" to 1,
            )

        mockMvc.post("/scraping/runs/$runId/batches") {
            with(authentication(token("write:jobs")))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsBytes(invalidBatch)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_ERROR") }
        }

        mockMvc.post("/scraping/runs/$runId/heartbeat") {
            with(authentication(token("write:jobs")))
            contentType = MediaType.APPLICATION_JSON
            content = """{"leaseToken":"${UUID.randomUUID()}"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("SCRAPING_LEASE_LOST") }
        }
    }

    private fun token(vararg scopes: String): JwtAuthenticationToken {
        val jwt =
            Jwt
                .withTokenValue("scraping-test-token")
                .header("alg", "none")
                .subject("scraper")
                .issuedAt(Instant.parse("2026-09-18T10:00:00Z"))
                .expiresAt(Instant.parse("2026-09-18T20:00:00Z"))
                .build()
        return JwtAuthenticationToken(jwt, scopes.map { SimpleGrantedAuthority("SCOPE_$it") })
    }
}
