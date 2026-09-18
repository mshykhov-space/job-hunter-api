package com.mshykhov.jobhunter.application.scraping

import com.mshykhov.jobhunter.application.common.ConflictException
import com.mshykhov.jobhunter.application.common.ScrapingLeaseLostException
import com.mshykhov.jobhunter.application.common.ScrapingSourceDisabledException
import com.mshykhov.jobhunter.application.common.ValidationException
import com.mshykhov.jobhunter.application.job.Category
import com.mshykhov.jobhunter.application.job.JobSource
import com.mshykhov.jobhunter.application.preference.SearchPreferences
import com.mshykhov.jobhunter.application.preference.UserPreferenceEntity
import com.mshykhov.jobhunter.application.preference.UserPreferenceFacade
import com.mshykhov.jobhunter.application.user.UserFacade
import com.mshykhov.jobhunter.support.AbstractIntegrationTest
import com.mshykhov.jobhunter.support.TestFixtures
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestPropertySource(properties = ["jobhunter.scraping.enabled-sources=linkedin"])
class ScrapingServiceIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var service: ScrapingService

    @Autowired
    lateinit var retentionService: ScrapingRetentionService

    @Autowired
    lateinit var userFacade: UserFacade

    @Autowired
    lateinit var preferenceFacade: UserPreferenceFacade

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

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
        addCriteria()
    }

    @Test
    fun `concurrent claims create one active run and one lease`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val claims =
                pool.invokeAll(
                    listOf(
                        Callable { service.claim(JobSource.LINKEDIN, "worker-a") },
                        Callable { service.claim(JobSource.LINKEDIN, "worker-b") },
                    ),
                ).map { it.get() }

            assertEquals(1, claims.count { it != null })
            assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM scraping_runs WHERE status = 'ACTIVE'", Long::class.java))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `expired lease rotates token resumes checkpoint and stops after three crashed attempts`() {
        val first = assertNotNull(service.claim(JobSource.LINKEDIN, "worker-a"))
        val batch = ScrapingBatchCommand(UUID.randomUUID(), emptyList(), mapOf("cursor" to "page-2"), 12)
        service.batch(
            first.runId,
            first.leaseToken,
            batch,
        )

        expire(first.runId)
        val second = assertNotNull(service.claim(JobSource.LINKEDIN, "worker-b"))
        assertEquals(first.runId, second.runId)
        assertNotEquals(first.leaseToken, second.leaseToken)
        assertEquals(mapOf("cursor" to "page-2"), second.checkpoint)
        assertEquals(first.since, second.since)
        assertEquals(2, second.attempt)
        assertEquals(0, service.batch(second.runId, second.leaseToken, batch).acceptedCount)
        assertFailsWith<ScrapingLeaseLostException> { service.heartbeat(first.runId, first.leaseToken) }

        expire(second.runId)
        val third = assertNotNull(service.claim(JobSource.LINKEDIN, "worker-c"))
        assertEquals(3, third.attempt)
        expire(third.runId)
        assertNull(service.claim(JobSource.LINKEDIN, "worker-d"))

        val status = service.status().single { it.source == JobSource.LINKEDIN }
        assertEquals(ScrapingRunStatus.FAILED, status.status)
        assertEquals(1, status.runsFailed)
    }

    @Test
    fun `batch replay is idempotent and changed content conflicts`() {
        val claim = assertNotNull(service.claim(JobSource.LINKEDIN, "worker"))
        val batchId = UUID.randomUUID()
        val command =
            ScrapingBatchCommand(
                batchId,
                listOf(TestFixtures.jobIngestRequest(source = JobSource.LINKEDIN)),
                mapOf("cursor" to "next"),
                3,
            )

        assertEquals(1, service.batch(claim.runId, claim.leaseToken, command).acceptedCount)
        assertEquals(1, service.batch(claim.runId, claim.leaseToken, command).acceptedCount)
        assertFailsWith<ConflictException> {
            service.batch(claim.runId, claim.leaseToken, command.copy(checkpoint = mapOf("cursor" to "other")))
        }

        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM scraping_batches", Long::class.java))
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM jobs WHERE source = 'LINKEDIN'", Long::class.java))
        assertEquals(3, jdbcTemplate.queryForObject("SELECT fetched_count FROM scraping_runs WHERE id = ?", Int::class.java, claim.runId))
    }

    @Test
    fun `batch rejects another source and rolls back checkpoint when job persistence fails`() {
        val claim = assertNotNull(service.claim(JobSource.LINKEDIN, "worker"))
        assertFailsWith<ValidationException> {
            service.batch(
                claim.runId,
                claim.leaseToken,
                ScrapingBatchCommand(UUID.randomUUID(), listOf(TestFixtures.jobIngestRequest(source = JobSource.DOU)), emptyMap(), 1),
            )
        }

        assertFailsWith<DataIntegrityViolationException> {
            service.batch(
                claim.runId,
                claim.leaseToken,
                ScrapingBatchCommand(
                    UUID.randomUUID(),
                    listOf(TestFixtures.jobIngestRequest(title = "x".repeat(501), source = JobSource.LINKEDIN)),
                    mapOf("cursor" to "must-not-commit"),
                    1,
                ),
            )
        }

        val checkpoint = jdbcTemplate.queryForObject("SELECT checkpoint::text FROM scraping_runs WHERE id = ?", String::class.java, claim.runId)
        assertEquals("{}", checkpoint)
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM scraping_batches", Long::class.java))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT fetched_count FROM scraping_runs WHERE id = ?", Int::class.java, claim.runId))
    }

    @Test
    fun `completion replay requires the same token and schedules next run`() {
        val claim = assertNotNull(service.claim(JobSource.LINKEDIN, "worker"))
        service.complete(claim.runId, claim.leaseToken)
        service.complete(claim.runId, claim.leaseToken)
        assertFailsWith<ScrapingLeaseLostException> { service.complete(claim.runId, UUID.randomUUID()) }

        val status = service.status().single { it.source == JobSource.LINKEDIN }
        assertEquals(ScrapingRunStatus.SUCCEEDED, status.status)
        assertEquals(1, status.runsSucceeded)
        assertTrue(status.nextRunAt.isAfter(requireNotNull(status.lastSuccessAt)))
    }

    @Test
    fun `explicit failures back off one and five minutes before terminal failure`() {
        val first = assertNotNull(service.claim(JobSource.LINKEDIN, "worker-a"))
        service.fail(first.runId, first.leaseToken, "UPSTREAM_TIMEOUT")
        assertEquals(60, retryDelaySeconds(first.runId))
        assertNull(service.claim(JobSource.LINKEDIN, "too-early"))

        makeRetryDue(first.runId)
        val second = assertNotNull(service.claim(JobSource.LINKEDIN, "worker-b"))
        service.fail(second.runId, second.leaseToken, "UPSTREAM_TIMEOUT")
        assertEquals(300, retryDelaySeconds(second.runId))

        makeRetryDue(second.runId)
        val third = assertNotNull(service.claim(JobSource.LINKEDIN, "worker-c"))
        service.fail(third.runId, third.leaseToken, "UPSTREAM_TIMEOUT")
        assertEquals(
            "FAILED",
            jdbcTemplate.queryForObject("SELECT status FROM scraping_runs WHERE id = ?", String::class.java, third.runId),
        )
        assertEquals(1, service.status().single { it.source == JobSource.LINKEDIN }.runsFailed)
    }

    @Test
    fun `every new run uses a one hour lookback including the first`() {
        val first = assertNotNull(service.claim(JobSource.LINKEDIN, "worker-a"))
        val firstStartedAt =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT started_at FROM scraping_runs WHERE id = ?",
                    java.sql.Timestamp::class.java,
                    first.runId,
                ),
            ).toInstant()
        assertEquals(firstStartedAt.minusSeconds(3_600), first.since)

        service.complete(first.runId, first.leaseToken)
        jdbcTemplate.update(
            "UPDATE scraping_sources SET next_run_at = now() - interval '1 second' WHERE source = 'linkedin'",
        )

        val second = assertNotNull(service.claim(JobSource.LINKEDIN, "worker-b"))
        val secondStartedAt =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT started_at FROM scraping_runs WHERE id = ?",
                    java.sql.Timestamp::class.java,
                    second.runId,
                ),
            ).toInstant()
        assertEquals(secondStartedAt.minusSeconds(3_600), second.since)
    }

    @Test
    fun `retention removes terminal run receipts without storing job bodies`() {
        val claim = assertNotNull(service.claim(JobSource.LINKEDIN, "worker"))
        service.batch(
            claim.runId,
            claim.leaseToken,
            ScrapingBatchCommand(UUID.randomUUID(), emptyList(), emptyMap(), 0),
        )
        service.complete(claim.runId, claim.leaseToken)
        jdbcTemplate.update(
            "UPDATE scraping_runs SET completed_at = now() - interval '31 days' WHERE id = ?",
            claim.runId,
        )

        assertEquals(1, retentionService.deleteExpiredMetadata())
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM scraping_runs", Long::class.java))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM scraping_batches", Long::class.java))
    }

    @Test
    fun `disabled or criteria-less source does not create successful work`() {
        assertNull(service.claim(JobSource.DOU, "worker"))
        jdbcTemplate.update("DELETE FROM user_preferences")
        assertNull(service.claim(JobSource.LINKEDIN, "worker"))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM scraping_runs", Long::class.java))
    }

    @Test
    fun `disabling a source fences progress but preserves checkpoint for re-enable`() {
        val claim = assertNotNull(service.claim(JobSource.LINKEDIN, "worker"))
        service.batch(
            claim.runId,
            claim.leaseToken,
            ScrapingBatchCommand(UUID.randomUUID(), emptyList(), mapOf("cursor" to "resume-here"), 4),
        )
        jdbcTemplate.update("UPDATE scraping_sources SET enabled = false WHERE source = 'linkedin'")

        assertFailsWith<ScrapingSourceDisabledException> { service.heartbeat(claim.runId, claim.leaseToken) }
        assertFailsWith<ScrapingSourceDisabledException> {
            service.batch(
                claim.runId,
                claim.leaseToken,
                ScrapingBatchCommand(UUID.randomUUID(), emptyList(), mapOf("cursor" to "later"), 1),
            )
        }
        assertFailsWith<ScrapingSourceDisabledException> { service.complete(claim.runId, claim.leaseToken) }
        service.fail(claim.runId, claim.leaseToken, "SOURCE_DISABLED")

        assertEquals(
            "ACTIVE",
            jdbcTemplate.queryForObject("SELECT status FROM scraping_runs WHERE id = ?", String::class.java, claim.runId),
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject("SELECT attempt_count FROM scraping_runs WHERE id = ?", Int::class.java, claim.runId),
        )
        assertEquals(
            """{"cursor": "resume-here"}""",
            jdbcTemplate.queryForObject("SELECT checkpoint::text FROM scraping_runs WHERE id = ?", String::class.java, claim.runId),
        )
    }

    private fun addCriteria() {
        val user = userFacade.findOrCreate("scraping-test-${UUID.randomUUID()}")
        preferenceFacade.save(
            UserPreferenceEntity(
                user = user,
                search =
                SearchPreferences(
                    categories = setOf(Category("kotlin")),
                    locations = listOf("Remote"),
                    remoteOnly = true,
                ),
            ),
        )
    }

    private fun expire(runId: UUID) {
        jdbcTemplate.update("UPDATE scraping_runs SET lease_expires_at = now() - interval '1 second' WHERE id = ?", runId)
    }

    private fun makeRetryDue(runId: UUID) {
        jdbcTemplate.update("UPDATE scraping_runs SET next_attempt_at = now() - interval '1 second' WHERE id = ?", runId)
    }

    private fun retryDelaySeconds(runId: UUID): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (next_attempt_at - updated_at))::int FROM scraping_runs WHERE id = ?",
                Int::class.java,
                runId,
            ),
        )
}
