package com.mshykhov.jobhunter.infrastructure.metrics

import com.mshykhov.jobhunter.application.job.JobSource
import com.mshykhov.jobhunter.application.settings.AiProvider
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchingMetricsTest {
    private val registry = SimpleMeterRegistry()
    private var backlog = 0L
    private val metrics = MatchingMetrics(registry, Supplier { backlog })

    @Test
    fun `should record evaluation counter with distinct tags per provider model and outcome`() {
        metrics.recordEvaluation(AiProvider.CODEX, "gpt-5.6-luna", "quota", Duration.ofMillis(120))
        metrics.recordEvaluation(AiProvider.OPENAI, "gpt-5-mini", "success", Duration.ofMillis(80))

        val quotaCount =
            registry
                .get(MatchingMetrics.AI_EVALUATIONS_METRIC)
                .tags("provider", "CODEX", "model", "gpt-5.6-luna", "outcome", "quota")
                .counter()
                .count()
        val successCount =
            registry
                .get(MatchingMetrics.AI_EVALUATIONS_METRIC)
                .tags("provider", "OPENAI", "model", "gpt-5-mini", "outcome", "success")
                .counter()
                .count()

        assertEquals(1.0, quotaCount)
        assertEquals(1.0, successCount)
    }

    @Test
    fun `should tag with the model id when it matches a known catalog entry`() {
        metrics.recordEvaluation(AiProvider.CODEX, "gpt-5.6-luna", "success", Duration.ofMillis(50))

        val counter =
            registry
                .get(MatchingMetrics.AI_EVALUATIONS_METRIC)
                .tags("provider", "CODEX", "model", "gpt-5.6-luna", "outcome", "success")
                .counter()

        assertEquals(1.0, counter.count())
    }

    @Test
    fun `should retain the production Codex Terra model in evaluation metrics`() {
        metrics.recordEvaluation(AiProvider.CODEX, "gpt-5.6-terra", "success", Duration.ofMillis(50))

        val counter = registry.get(MatchingMetrics.AI_EVALUATIONS_METRIC)
            .tags("provider", "CODEX", "model", "gpt-5.6-terra", "outcome", "success").counter()
        val timer = registry.get(MatchingMetrics.AI_EVALUATION_DURATION_METRIC)
            .tags("provider", "CODEX", "model", "gpt-5.6-terra").timer()

        assertEquals(1.0, counter.count())
        assertEquals(1, timer.count())
    }

    @Test
    fun `should tag with other and keep the raw model id out of the tag when the model is unknown`() {
        metrics.recordEvaluation(AiProvider.OPENAI, "some-user-typed-model", "success", Duration.ofMillis(50))

        val counter =
            registry
                .get(MatchingMetrics.AI_EVALUATIONS_METRIC)
                .tags("provider", "OPENAI", "model", "other", "outcome", "success")
                .counter()
        val timer = registry.get(MatchingMetrics.AI_EVALUATION_DURATION_METRIC).tags("provider", "OPENAI", "model", "other").timer()

        assertEquals(1.0, counter.count())
        assertEquals(1, timer.count())
        assertTrue(registry.meters.none { it.id.getTag("model") == "some-user-typed-model" })
    }

    @Test
    fun `should record evaluation duration under the timer metric`() {
        metrics.recordEvaluation(AiProvider.CODEX, "gpt-5.6-luna", "success", Duration.ofMillis(250))

        val timer =
            registry
                .get(MatchingMetrics.AI_EVALUATION_DURATION_METRIC)
                .tags("provider", "CODEX", "model", "gpt-5.6-luna")
                .timer()

        assertEquals(1, timer.count())
        assertEquals(250.0, timer.totalTime(TimeUnit.MILLISECONDS), 1.0)
    }

    @Test
    fun `should record ingest count tagged by source`() {
        metrics.recordIngest(JobSource.DOU, 5)
        metrics.recordIngest(JobSource.DJINNI, 3)

        assertEquals(5.0, registry.get(MatchingMetrics.JOBS_INGESTED_METRIC).tags("source", "dou").counter().count())
        assertEquals(3.0, registry.get(MatchingMetrics.JOBS_INGESTED_METRIC).tags("source", "djinni").counter().count())
    }

    @Test
    fun `should record purge count`() {
        metrics.recordPurge(14449)

        assertEquals(14449.0, registry.get(MatchingMetrics.JOBS_PURGED_METRIC).counter().count())
    }

    @Test
    fun `should accumulate purge count across multiple runs`() {
        metrics.recordPurge(10)
        metrics.recordPurge(5)

        assertEquals(15.0, registry.get(MatchingMetrics.JOBS_PURGED_METRIC).counter().count())
    }

    @Test
    fun `should reflect the backlog supplier value on each read`() {
        backlog = 42

        assertEquals(42.0, registry.get(MatchingMetrics.MATCHING_BACKLOG_METRIC).gauge().value())

        backlog = 7

        assertEquals(7.0, registry.get(MatchingMetrics.MATCHING_BACKLOG_METRIC).gauge().value())
    }

    @Test
    fun `should not propagate when the meter registry throws while recording an evaluation`() {
        val throwingMetrics = MatchingMetrics(ThrowingMeterRegistry(), Supplier { 0L })

        assertDoesNotThrow {
            throwingMetrics.recordEvaluation(AiProvider.OPENAI, "gpt-4o-mini", "success", Duration.ofMillis(10))
        }
    }

    @Test
    fun `should not propagate when the meter registry throws while recording an ingest`() {
        val throwingMetrics = MatchingMetrics(ThrowingMeterRegistry(), Supplier { 0L })

        assertDoesNotThrow {
            throwingMetrics.recordIngest(JobSource.DOU, 1)
        }
    }

    @Test
    fun `should not propagate when the meter registry throws while recording a purge`() {
        val throwingMetrics = MatchingMetrics(ThrowingMeterRegistry(), Supplier { 0L })

        assertDoesNotThrow {
            throwingMetrics.recordPurge(1)
        }
    }

    @Test
    fun `should render the documented metric names when scraped as prometheus text`() {
        val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        var prometheusBacklog = 0L
        val prometheusMetrics = MatchingMetrics(prometheusRegistry, Supplier { prometheusBacklog })
        prometheusBacklog = 12

        prometheusMetrics.recordEvaluation(AiProvider.CODEX, "gpt-5.6-luna", "quota", Duration.ofMillis(100))
        prometheusMetrics.recordIngest(JobSource.DOU, 4)
        prometheusMetrics.recordPurge(7)

        val scrape = prometheusRegistry.scrape()

        assertTrue(scrape.contains("jobhunter_ai_evaluations_total"), scrape)
        assertTrue(scrape.contains("jobhunter_ai_evaluation_duration_seconds"), scrape)
        assertTrue(scrape.contains("jobhunter_matching_backlog 12.0"), scrape)
        assertTrue(scrape.contains("jobhunter_jobs_ingested_total"), scrape)
        assertTrue(scrape.contains("jobhunter_jobs_purged_total"), scrape)
    }

    private class ThrowingMeterRegistry : SimpleMeterRegistry() {
        override fun counter(
            name: String,
            vararg tags: String,
        ): Counter = throw IllegalStateException("meter registry unavailable")
    }
}
