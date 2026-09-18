package com.mshykhov.jobhunter.application.job

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
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
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.domain.Persistable
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "jobs")
@EntityListeners(AuditingEntityListener::class)
class JobEntity(
    @Id
    private val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 500)
    var title: String,
    @Column(length = 300)
    val company: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    val group: JobGroupEntity,
    @Column(nullable = false, unique = true, length = 2048)
    val url: String,
    @Column(nullable = false, columnDefinition = "TEXT")
    var description: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val source: JobSource,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_data", nullable = false, columnDefinition = "jsonb")
    var rawData: Map<String, Any?> = emptyMap(),
    @Column(length = 200)
    var salary: String? = null,
    @Column(length = 300)
    var location: String? = null,
    var remote: Boolean? = null,
    @Column(name = "published_at")
    var publishedAt: Instant? = null,
    @Column(name = "matched_at")
    var matchedAt: Instant? = null,
    @Column(name = "match_attempts", nullable = false)
    var matchAttempts: Int = 0,
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @LastModifiedDate
    @Column(name = "updated_at", insertable = false)
    var updatedAt: Instant? = null,
) : Persistable<UUID> {
    @Transient
    private var isNew: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = isNew

    @PostPersist
    @PostLoad
    private fun markNotNew() {
        isNew = false
    }
}
