package com.mshykhov.jobhunter.api.rest.scraping

import com.mshykhov.jobhunter.api.rest.scraping.dto.ScrapingBatchRequest
import com.mshykhov.jobhunter.api.rest.scraping.dto.ScrapingBatchResponse
import com.mshykhov.jobhunter.api.rest.scraping.dto.ScrapingClaimRequest
import com.mshykhov.jobhunter.api.rest.scraping.dto.ScrapingClaimResponse
import com.mshykhov.jobhunter.api.rest.scraping.dto.ScrapingFailureRequest
import com.mshykhov.jobhunter.api.rest.scraping.dto.ScrapingLeaseRequest
import com.mshykhov.jobhunter.api.rest.scraping.dto.ScrapingStatusResponse
import com.mshykhov.jobhunter.application.common.ValidationException
import com.mshykhov.jobhunter.application.job.JobSource
import com.mshykhov.jobhunter.application.scraping.ScrapingService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/scraping")
class ScrapingController(private val service: ScrapingService) {
    @PostMapping("/sources/{source}/claim")
    @PreAuthorize("hasAuthority('SCOPE_write:jobs')")
    fun claim(
        @PathVariable source: String,
        @Valid @RequestBody request: ScrapingClaimRequest,
    ): ResponseEntity<ScrapingClaimResponse> {
        val jobSource =
            JobSource.entries.firstOrNull { it.value == source.lowercase() }
                ?: throw ValidationException("Unknown scraping source")
        val claim = service.claim(jobSource, request.workerId) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(ScrapingClaimResponse.from(claim))
    }

    @PostMapping("/runs/{runId}/heartbeat")
    @PreAuthorize("hasAuthority('SCOPE_write:jobs')")
    fun heartbeat(
        @PathVariable runId: UUID,
        @Valid @RequestBody request: ScrapingLeaseRequest,
    ): ResponseEntity<Void> {
        service.heartbeat(runId, request.leaseToken)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/runs/{runId}/batches")
    @PreAuthorize("hasAuthority('SCOPE_write:jobs')")
    fun batch(
        @PathVariable runId: UUID,
        @Valid @RequestBody request: ScrapingBatchRequest,
    ): ScrapingBatchResponse =
        ScrapingBatchResponse(service.batch(runId, request.leaseToken, request.toCommand()).acceptedCount)

    @PostMapping("/runs/{runId}/complete")
    @PreAuthorize("hasAuthority('SCOPE_write:jobs')")
    fun complete(
        @PathVariable runId: UUID,
        @Valid @RequestBody request: ScrapingLeaseRequest,
    ): ResponseEntity<Void> {
        service.complete(runId, request.leaseToken)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/runs/{runId}/fail")
    @PreAuthorize("hasAuthority('SCOPE_write:jobs')")
    fun fail(
        @PathVariable runId: UUID,
        @Valid @RequestBody request: ScrapingFailureRequest,
    ): ResponseEntity<Void> {
        service.fail(runId, request.leaseToken, request.reason)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('SCOPE_read:jobs')")
    fun status(): ScrapingStatusResponse = ScrapingStatusResponse.from(service.status())
}
