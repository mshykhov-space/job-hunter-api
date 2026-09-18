package com.mshykhov.jobhunter.application.scraping.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "scraping_sources")
class ScrapingSourceEntity(
    @Id
    @Column(length = 32)
    val source: String,
    @Column(nullable = false)
    var enabled: Boolean = false,
    @Column(name = "next_run_at", nullable = false)
    var nextRunAt: Instant,
    @Column(name = "last_success_at")
    var lastSuccessAt: Instant? = null,
    @Column(name = "last_error_at")
    var lastErrorAt: Instant? = null,
    @Column(name = "last_error_code", length = 64)
    var lastErrorCode: String? = null,
    @Column(name = "runs_started", nullable = false)
    var runsStarted: Long = 0,
    @Column(name = "runs_succeeded", nullable = false)
    var runsSucceeded: Long = 0,
    @Column(name = "runs_failed", nullable = false)
    var runsFailed: Long = 0,
    @Column(name = "fetched_count", nullable = false)
    var fetchedCount: Long = 0,
    @Column(name = "accepted_count", nullable = false)
    var acceptedCount: Long = 0,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
