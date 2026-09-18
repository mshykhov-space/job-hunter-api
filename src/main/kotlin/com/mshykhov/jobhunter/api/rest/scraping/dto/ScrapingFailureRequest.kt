package com.mshykhov.jobhunter.api.rest.scraping.dto

import jakarta.validation.constraints.Pattern
import java.util.UUID

data class ScrapingFailureRequest(
    val leaseToken: UUID,
    @field:Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$")
    val reason: String,
)
