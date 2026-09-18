package com.mshykhov.jobhunter.application.scraping.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
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
@Table(name = "scraping_batches")
class ScrapingBatchEntity(
    @Id
    @Column(name = "batch_id")
    private val id: UUID,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    val run: ScrapingRunEntity,
    @Column(name = "request_hash", nullable = false, length = 64)
    val requestHash: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    val checkpoint: Map<String, String>,
    @Column(name = "fetched_count", nullable = false)
    val fetchedCount: Int,
    @Column(name = "accepted_count", nullable = false)
    val acceptedCount: Int,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
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
