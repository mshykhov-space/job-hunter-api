package com.mshykhov.jobhunter.infrastructure.metrics

import com.mshykhov.jobhunter.support.AbstractIntegrationTest
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestPropertySource(properties = ["jobhunter.scraping.enabled-sources=linkedin"])
class ScrapingMetricReaderIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `gauges expose restart-safe source state and zero timestamps for never-run sources`() {
        jdbcTemplate.update(
            """
            UPDATE scraping_sources
            SET enabled = true, runs_started = 7, runs_succeeded = 5, runs_failed = 2,
                fetched_count = 140, accepted_count = 23,
                last_success_at = NULL, last_error_at = NULL
            WHERE source = 'linkedin'
            """.trimIndent(),
        )

        val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        ScrapingMetrics(prometheusRegistry, JdbcScrapingMetricReader(jdbcTemplate, Clock.systemUTC()))
        assertEquals(1.0, gauge(prometheusRegistry, ScrapingMetrics.ENABLED, "linkedin"))
        assertEquals(0.0, gauge(prometheusRegistry, ScrapingMetrics.LAST_SUCCESS, "linkedin"))
        assertEquals(0.0, gauge(prometheusRegistry, ScrapingMetrics.LAST_ERROR, "linkedin"))
        assertEquals(7.0, counter(prometheusRegistry, ScrapingMetrics.RUNS_STARTED, "linkedin"))
        assertEquals(5.0, counter(prometheusRegistry, ScrapingMetrics.RUNS_SUCCEEDED, "linkedin"))
        assertEquals(2.0, counter(prometheusRegistry, ScrapingMetrics.RUNS_FAILED, "linkedin"))
        assertEquals(140.0, counter(prometheusRegistry, ScrapingMetrics.JOBS_FETCHED, "linkedin"))
        assertEquals(23.0, counter(prometheusRegistry, ScrapingMetrics.JOBS_ACCEPTED, "linkedin"))
        assertEquals(0.0, gauge(prometheusRegistry, ScrapingMetrics.ENABLED, "dou"))

        val prometheus = prometheusRegistry.scrape()
        assertTrue(prometheus.contains("""jobhunter_scraping_source_enabled{source="linkedin"} 1.0"""))
        assertTrue(prometheus.contains("""jobhunter_scraping_last_success_timestamp_seconds{source="linkedin"} 0.0"""))
        assertTrue(prometheus.contains("""jobhunter_scraping_runs_started_total{source="linkedin"} 7.0"""), prometheus)
        assertTrue(prometheus.contains("""jobhunter_scraping_jobs_accepted_total{source="linkedin"} 23.0"""))
    }

    private fun gauge(
        registry: PrometheusMeterRegistry,
        name: String,
        source: String,
    ): Double = requireNotNull(registry.find(name).tag("source", source).gauge()).value()

    private fun counter(
        registry: PrometheusMeterRegistry,
        name: String,
        source: String,
    ): Double = requireNotNull(registry.find(name).tag("source", source).functionCounter()).count()
}
