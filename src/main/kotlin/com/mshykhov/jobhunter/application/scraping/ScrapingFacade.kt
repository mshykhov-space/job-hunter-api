package com.mshykhov.jobhunter.application.scraping

import com.mshykhov.jobhunter.application.scraping.persistence.ScrapingBatchEntity
import com.mshykhov.jobhunter.application.scraping.persistence.ScrapingBatchRepository
import com.mshykhov.jobhunter.application.scraping.persistence.ScrapingRunEntity
import com.mshykhov.jobhunter.application.scraping.persistence.ScrapingRunRepository
import com.mshykhov.jobhunter.application.scraping.persistence.ScrapingSourceEntity
import com.mshykhov.jobhunter.application.scraping.persistence.ScrapingSourceRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Component
@Transactional(readOnly = true)
class ScrapingFacade(
    private val sourceRepository: ScrapingSourceRepository,
    private val runRepository: ScrapingRunRepository,
    private val batchRepository: ScrapingBatchRepository,
) {
    fun findSources(): List<ScrapingSourceEntity> = sourceRepository.findAllByOrderBySourceAsc()

    fun findSourceForUpdate(source: String): ScrapingSourceEntity? = sourceRepository.findForUpdate(source)

    fun findActiveRunForUpdate(source: String): ScrapingRunEntity? =
        runRepository.findBySourceStateSourceAndStatus(source, ScrapingRunStatus.ACTIVE)

    fun findRunForUpdate(id: UUID): ScrapingRunEntity? = runRepository.findForUpdate(id)

    fun findRunSource(id: UUID): String? = runRepository.findSource(id)

    fun findLatestRun(source: String): ScrapingRunEntity? = runRepository.findTopBySourceStateSourceOrderByStartedAtDesc(source)

    fun findBatch(id: UUID): ScrapingBatchEntity? = batchRepository.findById(id).orElse(null)

    @Transactional
    fun saveSource(source: ScrapingSourceEntity): ScrapingSourceEntity = sourceRepository.save(source)

    @Transactional
    fun saveRun(run: ScrapingRunEntity): ScrapingRunEntity = runRepository.save(run)

    @Transactional
    fun saveBatch(batch: ScrapingBatchEntity): ScrapingBatchEntity = batchRepository.save(batch)

    @Transactional
    fun deleteTerminalBefore(threshold: Instant): Int = runRepository.deleteTerminalBefore(threshold)
}
