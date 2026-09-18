package com.mshykhov.jobhunter.application.scraping.persistence

import com.mshykhov.jobhunter.application.scraping.ScrapingRunStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "scraping_runs")
class ScrapingRunEntity(
    @Id
    private val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source", nullable = false)
    val sourceState: ScrapingSourceEntity,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ScrapingRunStatus = ScrapingRunStatus.ACTIVE,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "criteria_categories", nullable = false, columnDefinition = "jsonb")
    val criteriaCategories: Set<String>,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "criteria_locations", nullable = false, columnDefinition = "jsonb")
    val criteriaLocations: List<String>,
    @Column(name = "criteria_remote_only", nullable = false)
    val criteriaRemoteOnly: Boolean,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var checkpoint: Map<String, String> = emptyMap(),
    @Column(name = "since_at")
    val since: Instant? = null,
    @Column(name = "started_at", nullable = false)
    val startedAt: Instant,
    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,
    @Column(name = "lease_worker_id", length = 128)
    var leaseWorkerId: String? = null,
    @Column(name = "lease_token")
    var leaseToken: UUID? = null,
    @Column(name = "lease_expires_at")
    var leaseExpiresAt: Instant? = null,
    @Column(name = "fetched_count", nullable = false)
    var fetchedCount: Long = 0,
    @Column(name = "accepted_count", nullable = false)
    var acceptedCount: Long = 0,
    @Column(name = "failure_code", length = 64)
    var failureCode: String? = null,
    @Column(name = "completed_at")
    var completedAt: Instant? = null,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
) : Persistable<UUID> {
    @Transient
    private var newEntity: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = newEntity

    @PostPersist
    @PostLoad
    private fun markNotNew() {
        newEntity = false
    }
}
