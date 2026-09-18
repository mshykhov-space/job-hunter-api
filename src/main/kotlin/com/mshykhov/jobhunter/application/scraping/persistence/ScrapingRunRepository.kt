package com.mshykhov.jobhunter.application.scraping.persistence

import com.mshykhov.jobhunter.application.scraping.ScrapingRunStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface ScrapingRunRepository : JpaRepository<ScrapingRunEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findBySourceStateSourceAndStatus(
        source: String,
        status: ScrapingRunStatus,
    ): ScrapingRunEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ScrapingRunEntity r where r.id = :id")
    fun findForUpdate(id: UUID): ScrapingRunEntity?

    @Query("select r.sourceState.source from ScrapingRunEntity r where r.id = :id")
    fun findSource(id: UUID): String?

    fun findTopBySourceStateSourceAndStatusOrderByStartedAtDesc(
        source: String,
        status: ScrapingRunStatus,
    ): ScrapingRunEntity?

    fun findTopBySourceStateSourceOrderByStartedAtDesc(source: String): ScrapingRunEntity?

    @Modifying
    @Query(
        value = "DELETE FROM scraping_runs WHERE status IN ('SUCCEEDED', 'FAILED') AND completed_at < :threshold",
        nativeQuery = true,
    )
    fun deleteTerminalBefore(threshold: Instant): Int
}
