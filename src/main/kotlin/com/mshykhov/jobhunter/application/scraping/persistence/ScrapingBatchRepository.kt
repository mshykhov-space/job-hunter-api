package com.mshykhov.jobhunter.application.scraping.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ScrapingBatchRepository : JpaRepository<ScrapingBatchEntity, UUID>
