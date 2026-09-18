package com.mshykhov.jobhunter.api.rest.scraping.dto

import com.mshykhov.jobhunter.application.job.JobSource
import com.mshykhov.jobhunter.application.scraping.ScrapingRunStatus
import com.mshykhov.jobhunter.application.scraping.ScrapingSourceStatus
import java.time.Instant

data class ScrapingSourceStatusResponse(
    val source: JobSource,
    val enabled: Boolean,
    val status: ScrapingRunStatus?,
    val nextRunAt: Instant,
    val lastRunStartedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastErrorAt: Instant?,
    val lastErrorCode: String?,
    val currentRun: ScrapingCurrentRunResponse?,
    val runsStarted: Long,
    val runsSucceeded: Long,
    val runsFailed: Long,
    val fetchedCount: Long,
    val acceptedCount: Long,
) {
    companion object {
        fun from(status: ScrapingSourceStatus) =
            ScrapingSourceStatusResponse(
                status.source,
                status.enabled,
                status.status,
                status.nextRunAt,
                status.lastRunStartedAt,
                status.lastSuccessAt,
                status.lastErrorAt,
                status.lastErrorCode,
                status.currentRun?.let(ScrapingCurrentRunResponse::from),
                status.runsStarted,
                status.runsSucceeded,
                status.runsFailed,
                status.fetchedCount,
                status.acceptedCount,
            )
    }
}
