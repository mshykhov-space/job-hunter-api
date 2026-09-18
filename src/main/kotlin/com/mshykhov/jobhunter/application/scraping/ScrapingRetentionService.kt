package com.mshykhov.jobhunter.application.scraping

import com.mshykhov.jobhunter.infrastructure.scraping.ScrapingProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class ScrapingRetentionService(private val facade: ScrapingFacade, private val properties: ScrapingProperties, private val clock: Clock) {
    @Scheduled(fixedDelayString = "\${jobhunter.scraping.retention-interval:24h}")
    @Transactional
    fun deleteExpiredMetadata(): Int = facade.deleteTerminalBefore(Instant.now(clock).minus(properties.retentionPeriod))
}
