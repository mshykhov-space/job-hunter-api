package com.mshykhov.jobhunter.application.scraping

import com.mshykhov.jobhunter.api.rest.job.dto.JobIngestRequest
import java.util.UUID

data class ScrapingBatchCommand(val batchId: UUID, val jobs: List<JobIngestRequest>, val checkpoint: Map<String, String>, val fetchedCount: Int)
