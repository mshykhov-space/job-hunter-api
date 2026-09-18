package com.mshykhov.jobhunter.application.scraping

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.mshykhov.jobhunter.application.common.ConflictException
import com.mshykhov.jobhunter.application.common.NotFoundException
import com.mshykhov.jobhunter.application.common.ScrapingLeaseLostException
import com.mshykhov.jobhunter.application.common.ScrapingSourceDisabledException
import com.mshykhov.jobhunter.application.common.ValidationException
import com.mshykhov.jobhunter.application.criteria.SearchCriteriaService
import com.mshykhov.jobhunter.application.job.Category
import com.mshykhov.jobhunter.application.job.JobService
import com.mshykhov.jobhunter.application.job.JobSource
import com.mshykhov.jobhunter.application.scraping.persistence.ScrapingBatchEntity
import com.mshykhov.jobhunter.application.scraping.persistence.ScrapingRunEntity
import com.mshykhov.jobhunter.application.scraping.persistence.ScrapingSourceEntity
import com.mshykhov.jobhunter.infrastructure.scraping.ScrapingProperties
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

@Service
class ScrapingService(
    private val facade: ScrapingFacade,
    private val criteriaService: SearchCriteriaService,
    private val jobService: JobService,
    private val properties: ScrapingProperties,
    private val objectMapper: ObjectMapper,
    private val entityManager: EntityManager,
    private val clock: Clock,
) {
    @Transactional
    fun synchronizeEnabledSources() {
        val configured = properties.enabledSources.map { it.lowercase() }.toSet()
        val now = now()
        facade.findSources().forEach { source ->
            val enabled = source.source in configured
            if (source.enabled != enabled) {
                source.enabled = enabled
                source.updatedAt = now
                facade.saveSource(source)
            }
        }
    }

    @Transactional
    fun claim(
        source: JobSource,
        workerId: String,
    ): ScrapingClaim? {
        validateWorker(workerId)
        if (!properties.isEnabled(source)) return null
        val now = now()
        val state = lockSource(source)
        state.enabled = true
        val active = facade.findActiveRunForUpdate(source.value)
        if (active != null) return claimActive(state, active, workerId, now)
        if (state.nextRunAt.isAfter(now)) return null

        val aggregated = criteriaService.getAggregated(source)
        val criteria = ScrapingCriteria(aggregated.categories, aggregated.locations, aggregated.remoteOnly)
        if (!criteria.isExplicit()) {
            state.nextRunAt = now.plus(properties.scheduleInterval)
            state.updatedAt = now
            facade.saveSource(state)
            return null
        }
        val since = now.minus(properties.lookback)
        val run =
            facade.saveRun(
                ScrapingRunEntity(
                    sourceState = state,
                    criteriaCategories = criteria.categories.map { it.value }.toSet(),
                    criteriaLocations = criteria.locations,
                    criteriaRemoteOnly = criteria.remoteOnly,
                    since = since,
                    startedAt = now,
                    nextAttemptAt = now,
                    updatedAt = now,
                ),
            )
        state.runsStarted += 1
        state.updatedAt = now
        facade.saveSource(state)
        return issueLease(run, workerId, now)
    }

    @Transactional
    fun heartbeat(
        runId: UUID,
        leaseToken: UUID,
    ) {
        val (source, run) = lockRun(runId)
        val now = now()
        requireSourceEnabled(source)
        requireActiveLease(run, leaseToken, now)
        run.leaseExpiresAt = now.plus(properties.leaseDuration)
        run.updatedAt = now
        facade.saveRun(run)
    }

    @Transactional
    fun batch(
        runId: UUID,
        leaseToken: UUID,
        command: ScrapingBatchCommand,
    ): ScrapingBatchResult {
        validateBatch(command)
        val (source, run) = lockRun(runId)
        val now = now()
        requireSourceEnabled(source)
        requireActiveLease(run, leaseToken, now)
        val hash = requestHash(command)
        facade.findBatch(command.batchId)?.let { receipt ->
            if (receipt.run.id != runId || receipt.requestHash != hash) {
                throw ConflictException("Batch ID was already used with different content")
            }
            return ScrapingBatchResult(receipt.acceptedCount)
        }
        if (command.jobs.any { it.source.value != source.source }) {
            throw ValidationException("Every job in a batch must match the run source")
        }

        val accepted = jobService.ingest(command.jobs).size
        run.checkpoint = command.checkpoint.toSortedMap()
        run.fetchedCount += command.fetchedCount
        run.acceptedCount += accepted
        run.updatedAt = now
        source.fetchedCount += command.fetchedCount
        source.acceptedCount += accepted
        source.updatedAt = now
        facade.saveRun(run)
        facade.saveSource(source)
        facade.saveBatch(
            ScrapingBatchEntity(
                id = command.batchId,
                run = run,
                requestHash = hash,
                checkpoint = command.checkpoint.toSortedMap(),
                fetchedCount = command.fetchedCount,
                acceptedCount = accepted,
                createdAt = now,
            ),
        )
        entityManager.flush()
        return ScrapingBatchResult(accepted)
    }

    @Transactional
    fun complete(
        runId: UUID,
        leaseToken: UUID,
    ) {
        val (source, run) = lockRun(runId)
        if (run.status == ScrapingRunStatus.SUCCEEDED) {
            if (run.leaseToken != leaseToken) throw ScrapingLeaseLostException()
            return
        }
        val now = now()
        requireSourceEnabled(source)
        requireActiveLease(run, leaseToken, now)
        run.status = ScrapingRunStatus.SUCCEEDED
        run.leaseWorkerId = null
        run.leaseExpiresAt = null
        run.failureCode = null
        run.completedAt = now
        run.updatedAt = now
        source.nextRunAt = now.plus(properties.scheduleInterval)
        source.lastSuccessAt = now
        source.runsSucceeded += 1
        source.updatedAt = now
        facade.saveRun(run)
        facade.saveSource(source)
    }

    @Transactional
    fun fail(
        runId: UUID,
        leaseToken: UUID,
        reason: String,
    ) {
        if (!FAILURE_CODE.matches(reason)) throw ValidationException("Failure reason must be a bounded uppercase machine code")
        val (source, run) = lockRun(runId)
        val now = now()
        requireActiveLease(run, leaseToken, now)
        source.lastErrorAt = now
        source.lastErrorCode = reason
        run.failureCode = reason
        if (!source.enabled) {
            run.attemptCount = (run.attemptCount - 1).coerceAtLeast(0)
            run.nextAttemptAt = now
            run.updatedAt = now
            clearLease(run)
            source.nextRunAt = now
            source.updatedAt = now
            facade.saveRun(run)
            facade.saveSource(source)
        } else if (run.attemptCount >= properties.maxAttempts) {
            failTerminal(source, run, now, reason)
        } else {
            run.leaseWorkerId = null
            run.leaseToken = null
            run.leaseExpiresAt = null
            run.nextAttemptAt = now.plus(retryBackoff(run.attemptCount))
            run.updatedAt = now
            source.nextRunAt = run.nextAttemptAt
            source.updatedAt = now
            facade.saveRun(run)
            facade.saveSource(source)
        }
    }

    @Transactional(readOnly = true)
    fun status(): List<ScrapingSourceStatus> =
        facade.findSources().map { source ->
            val latest = facade.findLatestRun(source.source)
            val current =
                latest
                    ?.takeIf { it.status == ScrapingRunStatus.ACTIVE }
                    ?.let { run ->
                        val workerId = run.leaseWorkerId
                        val expiresAt = run.leaseExpiresAt
                        if (workerId != null && expiresAt != null) {
                            ScrapingCurrentRun(run.id, run.attemptCount, workerId, expiresAt)
                        } else {
                            null
                        }
                    }
            ScrapingSourceStatus(
                source = JobSource.fromValue(source.source),
                enabled = source.enabled,
                status = latest?.status,
                nextRunAt = source.nextRunAt,
                lastRunStartedAt = latest?.startedAt,
                lastSuccessAt = source.lastSuccessAt,
                lastErrorAt = source.lastErrorAt,
                lastErrorCode = source.lastErrorCode,
                currentRun = current,
                runsStarted = source.runsStarted,
                runsSucceeded = source.runsSucceeded,
                runsFailed = source.runsFailed,
                fetchedCount = source.fetchedCount,
                acceptedCount = source.acceptedCount,
            )
        }

    private fun claimActive(
        source: ScrapingSourceEntity,
        run: ScrapingRunEntity,
        workerId: String,
        now: Instant,
    ): ScrapingClaim? {
        val expiresAt = run.leaseExpiresAt
        if (run.leaseToken != null && expiresAt?.isAfter(now) == true) return null
        if (run.leaseToken != null) {
            source.lastErrorAt = now
            source.lastErrorCode = "LEASE_EXPIRED"
            run.failureCode = "LEASE_EXPIRED"
            if (run.attemptCount >= properties.maxAttempts) {
                failTerminal(source, run, now, "LEASE_EXHAUSTED")
                return null
            }
            clearLease(run)
        } else if (run.nextAttemptAt.isAfter(now)) {
            return null
        }
        return issueLease(run, workerId, now)
    }

    private fun issueLease(
        run: ScrapingRunEntity,
        workerId: String,
        now: Instant,
    ): ScrapingClaim {
        run.attemptCount += 1
        run.leaseWorkerId = workerId
        run.leaseToken = UUID.randomUUID()
        run.leaseExpiresAt = now.plus(properties.leaseDuration)
        run.nextAttemptAt = now
        run.updatedAt = now
        facade.saveRun(run)
        return ScrapingClaim(
            runId = run.id,
            leaseToken = requireNotNull(run.leaseToken),
            source = JobSource.fromValue(run.sourceState.source),
            criteria =
            ScrapingCriteria(
                run.criteriaCategories.mapTo(linkedSetOf()) { Category(it) },
                run.criteriaLocations,
                run.criteriaRemoteOnly,
            ),
            checkpoint = run.checkpoint,
            since = run.since,
            leaseExpiresAt = requireNotNull(run.leaseExpiresAt),
            attempt = run.attemptCount,
        )
    }

    private fun lockRun(runId: UUID): Pair<ScrapingSourceEntity, ScrapingRunEntity> {
        val sourceId = facade.findRunSource(runId) ?: throw NotFoundException("Scraping run not found")
        val source = facade.findSourceForUpdate(sourceId) ?: throw NotFoundException("Scraping source not found")
        val run = facade.findRunForUpdate(runId) ?: throw NotFoundException("Scraping run not found")
        return source to run
    }

    private fun lockSource(source: JobSource): ScrapingSourceEntity =
        facade.findSourceForUpdate(source.value) ?: throw NotFoundException("Scraping source not found")

    private fun requireActiveLease(
        run: ScrapingRunEntity,
        leaseToken: UUID,
        now: Instant,
    ) {
        if (run.status != ScrapingRunStatus.ACTIVE ||
            run.leaseToken != leaseToken ||
            run.leaseExpiresAt?.isAfter(now) != true
        ) {
            throw ScrapingLeaseLostException()
        }
    }

    private fun requireSourceEnabled(source: ScrapingSourceEntity) {
        if (!source.enabled) throw ScrapingSourceDisabledException()
    }

    private fun failTerminal(
        source: ScrapingSourceEntity,
        run: ScrapingRunEntity,
        now: Instant,
        reason: String,
    ) {
        run.status = ScrapingRunStatus.FAILED
        run.failureCode = reason
        run.completedAt = now
        run.updatedAt = now
        clearLease(run)
        source.nextRunAt = now.plus(properties.scheduleInterval)
        source.lastErrorAt = now
        source.lastErrorCode = reason
        source.runsFailed += 1
        source.updatedAt = now
        facade.saveRun(run)
        facade.saveSource(source)
    }

    private fun clearLease(run: ScrapingRunEntity) {
        run.leaseWorkerId = null
        run.leaseToken = null
        run.leaseExpiresAt = null
    }

    private fun validateWorker(workerId: String) {
        if (workerId.isBlank() || workerId.length > 128) throw ValidationException("Worker ID must contain 1 to 128 characters")
    }

    private fun validateBatch(command: ScrapingBatchCommand) {
        if (command.jobs.size > properties.maxBatchSize) {
            throw ValidationException("Batch must contain at most " + properties.maxBatchSize + " jobs")
        }
        if (command.fetchedCount < 0) throw ValidationException("Fetched count must not be negative")
        if (command.checkpoint.size > 32 ||
            command.checkpoint.any { (key, value) -> key.isBlank() || key.length > 64 || value.length > 2048 }
        ) {
            throw ValidationException("Checkpoint must contain at most 32 bounded string entries")
        }
    }

    private fun requestHash(command: ScrapingBatchCommand): String {
        val canonical =
            objectMapper
                .writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(
                    mapOf(
                        "jobs" to command.jobs,
                        "checkpoint" to command.checkpoint.toSortedMap(),
                        "fetchedCount" to command.fetchedCount,
                    ),
                )
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))
    }

    private fun retryBackoff(attemptCount: Int) =
        properties.retryBackoffs.getOrElse((attemptCount - 1).coerceAtLeast(0)) { properties.retryBackoffs.last() }

    private fun now(): Instant = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)

    private companion object {
        val FAILURE_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
    }
}
