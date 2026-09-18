package com.mshykhov.jobhunter.application.scraping

import com.mshykhov.jobhunter.application.job.JobSource
import java.time.Instant

data class ScrapingSourceStatus(
    val source: JobSource,
    val enabled: Boolean,
    val status: ScrapingRunStatus?,
    val nextRunAt: Instant,
    val lastRunStartedAt: Instant?,
    val lastSuccessAt: Instant?,
    val lastErrorAt: Instant?,
    val lastErrorCode: String?,
    val currentRun: ScrapingCurrentRun?,
    val runsStarted: Long,
    val runsSucceeded: Long,
    val runsFailed: Long,
    val fetchedCount: Long,
    val acceptedCount: Long,
)
