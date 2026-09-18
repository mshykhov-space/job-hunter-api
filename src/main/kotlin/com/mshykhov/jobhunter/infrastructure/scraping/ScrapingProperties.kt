package com.mshykhov.jobhunter.infrastructure.scraping

import com.mshykhov.jobhunter.application.job.JobSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

@ConfigurationProperties(prefix = "jobhunter.scraping")
data class ScrapingProperties(
    val enabledSources: Set<String> = emptySet(),
    val scheduleInterval: Duration = Duration.ofMinutes(15),
    val leaseDuration: Duration = Duration.ofMinutes(5),
    val sinceOverlap: Duration = Duration.ofHours(24),
    val retryBackoffs: List<Duration> = listOf(Duration.ofMinutes(1), Duration.ofMinutes(5)),
    val maxAttempts: Int = 3,
    val maxBatchSize: Int = 250,
    val maxBatchRequestSize: DataSize = DataSize.ofMegabytes(8),
    val retentionPeriod: Duration = Duration.ofDays(30),
) {
    init {
        val known = JobSource.entries.map { it.value }.toSet()
        require(enabledSources.all { it.lowercase() in known }) { "SCRAPING_ENABLED_SOURCES contains an unknown source" }
        require(!scheduleInterval.isNegative && !scheduleInterval.isZero) { "Scraping schedule interval must be positive" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "Scraping lease duration must be positive" }
        require(!sinceOverlap.isNegative) { "Scraping since overlap must not be negative" }
        require(maxAttempts in 1..3) { "Scraping max attempts must be between 1 and 3" }
        require(retryBackoffs.size >= maxAttempts - 1 && retryBackoffs.all { !it.isNegative && !it.isZero }) {
            "Scraping retry backoffs must cover every retry and be positive"
        }
        require(maxBatchSize in 1..250) { "Scraping max batch size must be between 1 and 250" }
        require(maxBatchRequestSize.toBytes() in 1 until Int.MAX_VALUE) { "Scraping batch request size is out of range" }
        require(!retentionPeriod.isNegative && !retentionPeriod.isZero) { "Scraping retention period must be positive" }
    }

    fun isEnabled(source: JobSource): Boolean = enabledSources.any { it.equals(source.value, ignoreCase = true) }
}
