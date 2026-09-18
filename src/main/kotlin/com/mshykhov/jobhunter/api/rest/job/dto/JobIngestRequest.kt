package com.mshykhov.jobhunter.api.rest.job.dto

import com.mshykhov.jobhunter.application.job.Category
import com.mshykhov.jobhunter.application.job.JobEntity
import com.mshykhov.jobhunter.application.job.JobGroupEntity
import com.mshykhov.jobhunter.application.job.JobSource
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class JobIngestRequest(
    @field:NotBlank
    @field:Size(max = 500)
    val title: String,
    @field:Size(max = 300)
    val company: String? = null,
    @field:NotBlank
    @field:Size(max = 2048)
    val url: String,
    val description: String = "",
    val source: JobSource,
    @field:Size(max = 200)
    val salary: String? = null,
    @field:Size(max = 300)
    val location: String? = null,
    val remote: Boolean? = null,
    val publishedAt: String? = null,
    val rawData: Map<String, Any?> = emptyMap(),
    val category: Category,
) {
    fun toEntity(
        parsedPublishedAt: Instant?,
        group: JobGroupEntity,
        seenAt: Instant,
    ): JobEntity =
        JobEntity(
            title = title,
            company = company,
            group = group,
            url = url,
            description = description,
            source = source,
            rawData = rawData,
            salary = salary,
            location = location,
            remote = remote,
            publishedAt = parsedPublishedAt,
            lastSeenAt = seenAt,
        )
}
