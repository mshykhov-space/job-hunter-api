package com.mshykhov.jobhunter.api.rest.scraping.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ScrapingClaimRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val workerId: String,
)
