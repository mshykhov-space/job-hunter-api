package com.mshykhov.jobhunter.infrastructure.metrics

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class JdbcScrapingMetricReader(private val jdbcTemplate: JdbcTemplate, private val clock: Clock) : ScrapingMetricReader {
    @Volatile
    private var cached: Map<String, ScrapingMetricSnapshot> = emptyMap()

    @Volatile
    private var refreshAfter: Instant = Instant.MIN

    override fun read(source: String): ScrapingMetricSnapshot {
        val now = Instant.now(clock)
        if (!now.isBefore(refreshAfter)) refresh(now)
        return cached[source] ?: ZERO
    }

    private fun refresh(now: Instant) {
        synchronized(this) {
            if (now.isBefore(refreshAfter)) return
            cached =
                jdbcTemplate
                    .query(
                        """
                        SELECT source, enabled, last_success_at, last_error_at, runs_started,
                               runs_succeeded, runs_failed, fetched_count, accepted_count
                        FROM scraping_sources
                        """.trimIndent(),
                    ) { row, _ ->
                        row.getString("source") to
                            ScrapingMetricSnapshot(
                                enabled = if (row.getBoolean("enabled")) 1 else 0,
                                lastSuccessTimestampSeconds = row.getTimestamp("last_success_at")?.toInstant()?.epochSecond ?: 0,
                                lastErrorTimestampSeconds = row.getTimestamp("last_error_at")?.toInstant()?.epochSecond ?: 0,
                                runsStarted = row.getLong("runs_started"),
                                runsSucceeded = row.getLong("runs_succeeded"),
                                runsFailed = row.getLong("runs_failed"),
                                jobsFetched = row.getLong("fetched_count"),
                                jobsAccepted = row.getLong("accepted_count"),
                            )
                    }.toMap()
            refreshAfter = now.plus(CACHE_DURATION)
        }
    }

    private companion object {
        val CACHE_DURATION: Duration = Duration.ofSeconds(10)
        val ZERO = ScrapingMetricSnapshot(0, 0, 0, 0, 0, 0, 0, 0)
    }
}
