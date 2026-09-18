package com.mshykhov.jobhunter.api.rest.scraping.dto

import com.mshykhov.jobhunter.application.job.JobSource
import com.mshykhov.jobhunter.application.scraping.ScrapingClaim
import java.time.Instant
import java.util.UUID

data class ScrapingClaimResponse(
    val runId: UUID,
    val leaseToken: UUID,
    val source: JobSource,
    val criteria: ScrapingCriteriaResponse,
    val checkpoint: Map<String, String>,
    val since: Instant?,
    val leaseExpiresAt: Instant,
) {
    companion object {
        fun from(claim: ScrapingClaim) =
            ScrapingClaimResponse(
                claim.runId,
                claim.leaseToken,
                claim.source,
                ScrapingCriteriaResponse.from(claim.criteria),
                claim.checkpoint,
                claim.since,
                claim.leaseExpiresAt,
            )
    }
}
