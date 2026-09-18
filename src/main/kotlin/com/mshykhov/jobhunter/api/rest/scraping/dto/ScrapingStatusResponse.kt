package com.mshykhov.jobhunter.api.rest.scraping.dto

import com.mshykhov.jobhunter.application.scraping.ScrapingSourceStatus

data class ScrapingStatusResponse(val sources: List<ScrapingSourceStatusResponse>) {
    companion object {
        fun from(statuses: List<ScrapingSourceStatus>) = ScrapingStatusResponse(statuses.map(ScrapingSourceStatusResponse::from))
    }
}
