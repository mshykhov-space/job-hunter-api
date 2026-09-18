package com.mshykhov.jobhunter.api.rest.scraping.dto

import com.mshykhov.jobhunter.api.rest.job.dto.JobIngestRequest
import com.mshykhov.jobhunter.application.scraping.ScrapingBatchCommand
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.util.UUID

data class ScrapingBatchRequest(
    val leaseToken: UUID,
    val batchId: UUID,
    @field:Valid
    @field:Size(max = 250)
    val jobs: List<JobIngestRequest>,
    @field:Size(max = 32)
    val checkpoint: Map<String, String>,
    @field:Min(0)
    @field:Max(Int.MAX_VALUE.toLong())
    val fetchedCount: Int,
) {
    fun toCommand() = ScrapingBatchCommand(batchId, jobs, checkpoint, fetchedCount)
}
