package com.mshykhov.jobhunter.application.scraping

import com.mshykhov.jobhunter.application.job.JobSource
import java.time.Instant
import java.util.UUID

data class ScrapingClaim(
    val runId: UUID,
    val leaseToken: UUID,
    val source: JobSource,
    val criteria: ScrapingCriteria,
    val checkpoint: Map<String, String>,
    val since: Instant?,
    val leaseExpiresAt: Instant,
    val attempt: Int,
)
