package com.mshykhov.jobhunter.api.rest.scraping.dto

import com.mshykhov.jobhunter.application.scraping.ScrapingCurrentRun
import java.time.Instant
import java.util.UUID

data class ScrapingCurrentRunResponse(val runId: UUID, val attempt: Int, val workerId: String, val leaseExpiresAt: Instant) {
    companion object {
        fun from(run: ScrapingCurrentRun) = ScrapingCurrentRunResponse(run.runId, run.attempt, run.workerId, run.leaseExpiresAt)
    }
}
