package com.mshykhov.jobhunter.api.rest.job

import com.mshykhov.jobhunter.api.rest.job.dto.BulkUpdateStatusRequest
import com.mshykhov.jobhunter.api.rest.job.dto.JobCheckRequest
import com.mshykhov.jobhunter.api.rest.job.dto.JobCheckResponse
import com.mshykhov.jobhunter.api.rest.job.dto.JobGroupDetailResponse
import com.mshykhov.jobhunter.api.rest.job.dto.JobIngestRequest
import com.mshykhov.jobhunter.api.rest.job.dto.JobResponse
import com.mshykhov.jobhunter.api.rest.job.dto.PaginatedUserJobGroupResponse
import com.mshykhov.jobhunter.api.rest.job.dto.RematchResponse
import com.mshykhov.jobhunter.api.rest.job.dto.UpdateJobStatusRequest
import com.mshykhov.jobhunter.api.rest.job.dto.UserJobGroupFilterRequest
import com.mshykhov.jobhunter.api.rest.job.dto.UserJobGroupResponse
import com.mshykhov.jobhunter.application.job.JobService
import com.mshykhov.jobhunter.application.matching.JobMatchingService
import com.mshykhov.jobhunter.application.userjob.UserJobGroupService
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/jobs")
class JobController(
    private val jobService: JobService,
    private val userJobGroupService: UserJobGroupService,
    private val jobMatchingService: JobMatchingService,
) {
    @PostMapping("/ingest")
    @PreAuthorize("hasAuthority('SCOPE_write:jobs') and hasAuthority('SCOPE_read:criteria')")
    fun ingest(
        @Valid @RequestBody request: List<JobIngestRequest>,
    ): List<JobResponse> = jobService.ingest(request).map { JobResponse.from(it) }

    @PostMapping("/check")
    @PreAuthorize("hasAuthority('SCOPE_write:jobs') and hasAuthority('SCOPE_read:criteria')")
    fun checkJobs(
        @Valid @RequestBody requests: List<JobCheckRequest>,
    ): JobCheckResponse = jobService.checkJobs(requests)

    @PostMapping("/search")
    @PreAuthorize("hasAuthority('SCOPE_read:jobs')")
    fun search(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody filter: UserJobGroupFilterRequest,
    ): PaginatedUserJobGroupResponse = userJobGroupService.search(jwt.subject, filter)

    @GetMapping("/groups/{groupId}")
    @PreAuthorize("hasAuthority('SCOPE_read:jobs')")
    fun getGroupDetail(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable groupId: UUID,
    ): JobGroupDetailResponse = userJobGroupService.getGroupDetail(jwt.subject, groupId)

    @PatchMapping("/groups/{groupId}/status")
    @PreAuthorize("hasAuthority('SCOPE_write:jobs')")
    fun updateGroupStatus(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable groupId: UUID,
        @Valid @RequestBody request: UpdateJobStatusRequest,
    ): UserJobGroupResponse = userJobGroupService.updateGroupStatus(jwt.subject, groupId, request.status)

    @PatchMapping("/groups/status")
    @PreAuthorize("hasAuthority('SCOPE_write:jobs')")
    fun bulkUpdateGroupStatus(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: BulkUpdateStatusRequest,
    ): List<UserJobGroupResponse> = userJobGroupService.bulkUpdateGroupStatus(jwt.subject, request.groupIds, request.status)

    @PostMapping("/rematch")
    @PreAuthorize("hasAuthority('SCOPE_manage:jobs')")
    fun rematch(
        @RequestParam(required = false) since: Instant?,
    ): RematchResponse {
        val count = jobMatchingService.rematch(since)
        return RematchResponse(jobsQueued = count)
    }
}
