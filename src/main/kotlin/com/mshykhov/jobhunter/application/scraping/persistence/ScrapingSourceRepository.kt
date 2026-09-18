package com.mshykhov.jobhunter.application.scraping.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface ScrapingSourceRepository : JpaRepository<ScrapingSourceEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScrapingSourceEntity s where s.source = :source")
    fun findForUpdate(source: String): ScrapingSourceEntity?

    fun findAllByOrderBySourceAsc(): List<ScrapingSourceEntity>
}
