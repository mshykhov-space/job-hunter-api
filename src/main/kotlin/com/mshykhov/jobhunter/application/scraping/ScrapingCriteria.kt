package com.mshykhov.jobhunter.application.scraping

import com.mshykhov.jobhunter.application.job.Category

data class ScrapingCriteria(val categories: Set<Category>, val locations: List<String>, val remoteOnly: Boolean) {
    fun isExplicit(): Boolean = categories.isNotEmpty() || locations.isNotEmpty() || remoteOnly
}
