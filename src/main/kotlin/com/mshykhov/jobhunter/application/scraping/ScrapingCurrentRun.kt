package com.mshykhov.jobhunter.application.scraping

import java.time.Instant
import java.util.UUID

data class ScrapingCurrentRun(val runId: UUID, val attempt: Int, val workerId: String, val leaseExpiresAt: Instant)
