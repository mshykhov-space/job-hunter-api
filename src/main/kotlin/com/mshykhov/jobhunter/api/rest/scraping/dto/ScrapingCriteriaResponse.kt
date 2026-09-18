package com.mshykhov.jobhunter.api.rest.scraping.dto

import com.mshykhov.jobhunter.application.job.Category
import com.mshykhov.jobhunter.application.scraping.ScrapingCriteria

data class ScrapingCriteriaResponse(val categories: Set<Category>, val locations: List<String>, val remoteOnly: Boolean) {
    companion object {
        fun from(criteria: ScrapingCriteria) =
            ScrapingCriteriaResponse(criteria.categories, criteria.locations, criteria.remoteOnly)
    }
}
