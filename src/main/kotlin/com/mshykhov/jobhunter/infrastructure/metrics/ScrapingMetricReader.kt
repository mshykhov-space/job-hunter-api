package com.mshykhov.jobhunter.infrastructure.metrics

interface ScrapingMetricReader {
    fun read(source: String): ScrapingMetricSnapshot
}
