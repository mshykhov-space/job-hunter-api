package com.mshykhov.jobhunter.infrastructure.metrics

import com.mshykhov.jobhunter.application.job.JobSource
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class ScrapingMetrics(meterRegistry: MeterRegistry, reader: ScrapingMetricReader) {
    init {
        JobSource.entries.forEach { source ->
            register(meterRegistry, reader, ENABLED, source) { it.enabled }
            register(meterRegistry, reader, LAST_SUCCESS, source) { it.lastSuccessTimestampSeconds }
            register(meterRegistry, reader, LAST_ERROR, source) { it.lastErrorTimestampSeconds }
            registerCounter(meterRegistry, reader, RUNS_STARTED, source) { it.runsStarted }
            registerCounter(meterRegistry, reader, RUNS_SUCCEEDED, source) { it.runsSucceeded }
            registerCounter(meterRegistry, reader, RUNS_FAILED, source) { it.runsFailed }
            registerCounter(meterRegistry, reader, JOBS_FETCHED, source) { it.jobsFetched }
            registerCounter(meterRegistry, reader, JOBS_ACCEPTED, source) { it.jobsAccepted }
        }
    }

    private fun registerCounter(
        registry: MeterRegistry,
        reader: ScrapingMetricReader,
        name: String,
        source: JobSource,
        value: (ScrapingMetricSnapshot) -> Long,
    ) {
        FunctionCounter
            .builder(name, reader) { value(it.read(source.value)).toDouble() }
            .tag("source", source.value)
            .register(registry)
    }

    private fun register(
        registry: MeterRegistry,
        reader: ScrapingMetricReader,
        name: String,
        source: JobSource,
        value: (ScrapingMetricSnapshot) -> Long,
    ) {
        Gauge
            .builder(name, reader) { value(it.read(source.value)).toDouble() }
            .tag("source", source.value)
            .register(registry)
    }

    companion object {
        const val ENABLED = "jobhunter.scraping.source.enabled"
        const val LAST_SUCCESS = "jobhunter.scraping.last.success.timestamp.seconds"
        const val LAST_ERROR = "jobhunter.scraping.last.error.timestamp.seconds"
        const val RUNS_STARTED = "jobhunter.scraping.runs.started"
        const val RUNS_SUCCEEDED = "jobhunter.scraping.runs.succeeded"
        const val RUNS_FAILED = "jobhunter.scraping.runs.failed"
        const val JOBS_FETCHED = "jobhunter.scraping.jobs.fetched"
        const val JOBS_ACCEPTED = "jobhunter.scraping.jobs.accepted"
    }
}
