package com.mshykhov.jobhunter.application.matching

import com.mshykhov.jobhunter.application.ai.AiClientChain
import com.mshykhov.jobhunter.application.ai.AiUseCase
import com.mshykhov.jobhunter.application.ai.ChatClientFactory
import com.mshykhov.jobhunter.application.ai.JobRelevanceEvaluator
import com.mshykhov.jobhunter.application.ai.UserAiProviderService
import com.mshykhov.jobhunter.application.job.JobEntity
import com.mshykhov.jobhunter.application.job.JobFacade
import com.mshykhov.jobhunter.application.job.JobGroupEntity
import com.mshykhov.jobhunter.application.preference.UserPreferenceEntity
import com.mshykhov.jobhunter.application.preference.UserPreferenceFacade
import com.mshykhov.jobhunter.application.statistics.DecisionOutcome
import com.mshykhov.jobhunter.application.statistics.UserJobGroupDecisionFacade
import com.mshykhov.jobhunter.application.userjob.UserJobGroupEntity
import com.mshykhov.jobhunter.application.userjob.UserJobGroupFacade
import com.mshykhov.jobhunter.application.userjob.UserJobStatus
import com.mshykhov.jobhunter.infrastructure.ai.AiProperties
import com.mshykhov.jobhunter.infrastructure.matching.MatchingProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val logger = KotlinLogging.logger {}

@Service
class JobMatchingService(
    private val jobFacade: JobFacade,
    private val userPreferenceFacade: UserPreferenceFacade,
    private val userJobGroupFacade: UserJobGroupFacade,
    private val decisionFacade: UserJobGroupDecisionFacade,
    private val userAiProviderService: UserAiProviderService,
    private val jobRelevanceEvaluator: JobRelevanceEvaluator,
    private val chatClientFactory: ChatClientFactory,
    private val aiProperties: AiProperties,
    private val matchingProperties: MatchingProperties,
    private val clock: Clock,
) {
    private val coldFilterChain = ColdFilterChain()

    fun processUnmatchedJobs(): MatchingOutcome {
        val page = jobFacade.findUnmatched(matchingProperties.batchSize, matchingProperties.maxAttempts)
        if (page.isEmpty()) return MatchingOutcome.IDLE

        val preferences = userPreferenceFacade.findAll()
        if (preferences.isEmpty()) {
            markMatched(page)
            return MatchingOutcome.COMPLETED
        }

        val groupIds = page.map { it.group.id }.distinct()
        val fanoutLimit = matchingProperties.batchSize * GROUP_FANOUT_LIMIT
        val jobs = jobFacade.findByGroupIds(groupIds, fanoutLimit, matchingProperties.maxAttempts)
        if (jobs.size >= fanoutLimit) {
            logger.warn {
                "Group re-fetch truncated at $fanoutLimit jobs across ${groupIds.size} groups - some groups may be split"
            }
        }
        val jobsByGroup = jobs.groupBy { it.group }
        val userChains = buildUserChains(preferences)

        logger.info {
            "Matching ${jobs.size} jobs (${jobsByGroup.size} groups) against ${preferences.size} user preferences " +
                "(${userChains.size} AI-enabled)"
        }

        val semaphore = Semaphore(aiProperties.matching.concurrency)
        val results =
            runBlocking(Dispatchers.IO) {
                jobsByGroup.entries
                    .map { (group, groupJobs) ->
                        async {
                            semaphore.withPermit {
                                processGroup(group, groupJobs, preferences, userChains)
                            }
                        }
                    }.awaitAll()
            }

        val matchedJobs = results.filterIsInstance<MatchResult.Success>().flatMap { it.jobs }
        val failedResults = results.filterIsInstance<MatchResult.Failure>()
        val totalStats = results.fold(MatchingStats()) { acc, r -> acc.merge(r.stats) }

        if (matchedJobs.isNotEmpty()) markMatched(matchedJobs)
        if (totalStats.aiEvaluated > 0 && failedResults.isNotEmpty()) {
            jobFacade.incrementMatchAttempts(failedResults.flatMap { it.jobs }.map { it.id })
        }

        logger.info {
            "Matching complete: ${matchedJobs.size}/${jobs.size} processed (${failedResults.size} failed) - ${totalStats.summary()}"
        }

        return if (totalStats.aiFailed > 0 && totalStats.aiEvaluated == 0) {
            MatchingOutcome.AI_UNAVAILABLE
        } else {
            MatchingOutcome.COMPLETED
        }
    }

    private fun processGroup(
        group: JobGroupEntity,
        groupJobs: List<JobEntity>,
        preferences: List<UserPreferenceEntity>,
        userChains: Map<UUID, AiClientChain>,
    ): MatchResult {
        val stats = MatchingStats()
        return try {
            val representative = selectRepresentative(groupJobs)
            val userJobGroups = matchGroupToUsers(group, groupJobs, representative, preferences, userChains, stats)
            if (userJobGroups.isNotEmpty()) {
                userJobGroupFacade.saveAll(userJobGroups)
                stats.saved += userJobGroups.size
                logger.info {
                    "Group '${group.title}' (${groupJobs.size} jobs) matched to ${userJobGroups.size} users " +
                        "(scores: ${userJobGroups.map { it.aiRelevanceScore }})"
                }
            }
            if (stats.aiFailed > 0) {
                logger.warn {
                    "Group '${group.title}' left unmatched: ${stats.aiFailed} AI evaluation(s) failed"
                }
                MatchResult.Failure(groupJobs, stats)
            } else {
                MatchResult.Success(groupJobs, stats)
            }
        } catch (e: Exception) {
            logger.error(e) { "Matching failed for group '${group.title}'" }
            MatchResult.Failure(groupJobs, stats)
        }
    }

    private fun selectRepresentative(groupJobs: List<JobEntity>): JobEntity = groupJobs.maxBy { it.description.length }

    private fun matchGroupToUsers(
        group: JobGroupEntity,
        groupJobs: List<JobEntity>,
        representative: JobEntity,
        preferences: List<UserPreferenceEntity>,
        userChains: Map<UUID, AiClientChain>,
        stats: MatchingStats,
    ): List<UserJobGroupEntity> {
        val existingByUserId = userJobGroupFacade.findByGroupId(group.id).associateBy { it.user.id }
        val userJobGroups = mutableListOf<UserJobGroupEntity>()

        for (preference in preferences) {
            val existing = existingByUserId[preference.user.id]
            val filterResult = coldFilterChain.evaluate(representative, preference)
            if (filterResult is FilterResult.Rejected) {
                logger.debug {
                    "Group '${group.title}' rejected for user ${preference.user.id} " +
                        "by [${filterResult.filter}]: ${filterResult.reason}"
                }
                decisionFacade.upsert(
                    preference.user,
                    group,
                    groupJobs,
                    DecisionOutcome.COLD_REJECTED,
                    coldFilter = filterResult.filter,
                )
                removeUnreviewedMatch(existing)
                stats.coldRejected++
                continue
            }

            val chain = userChains[preference.user.id]

            when {
                chain != null -> {
                    val result = evaluateWithAi(group, groupJobs, representative, preference, chain, stats, existing)
                    if (result != null) userJobGroups += result
                }
                preference.matching.matchWithAi -> {
                    logger.warn {
                        "Group '${group.title}' has no result for user ${preference.user.id}: " +
                            "matchWithAi is enabled but the provider chain has no usable links"
                    }
                    stats.aiFailed++
                }
                else -> {
                    stats.coldOnly++
                    decisionFacade.upsert(preference.user, group, groupJobs, DecisionOutcome.COLD_ONLY)
                    userJobGroups += existing?.apply {
                        aiRelevanceScore = 0
                        aiReasoning = COLD_ONLY_REASONING
                    } ?: UserJobGroupEntity(
                        user = preference.user,
                        group = group,
                        aiRelevanceScore = 0,
                        aiReasoning = COLD_ONLY_REASONING,
                    )
                }
            }
        }

        return userJobGroups
    }

    private fun evaluateWithAi(
        group: JobGroupEntity,
        groupJobs: List<JobEntity>,
        representative: JobEntity,
        preference: UserPreferenceEntity,
        chain: AiClientChain,
        stats: MatchingStats,
        existing: UserJobGroupEntity?,
    ): UserJobGroupEntity? {
        val aiResult =
            try {
                jobRelevanceEvaluator.evaluate(representative, preference, chain)
            } catch (e: Exception) {
                logger.warn(e) { "AI evaluation failed for group '${group.title}', user ${preference.user.id}" }
                stats.aiFailed++
                return null
            }
        stats.aiEvaluated++

        backfillRemoteIfNeeded(representative, aiResult.inferredRemote)

        if (preference.search.remoteOnly && !aiResult.inferredRemote) {
            logger.debug {
                "Group '${group.title}' post-AI rejected for user ${preference.user.id}: " +
                    "remoteOnly but inferredRemote=false"
            }
            decisionFacade.upsert(
                preference.user,
                group,
                groupJobs,
                DecisionOutcome.AI_REJECTED_REMOTE,
                aiScore = aiResult.score,
                inferredRemote = aiResult.inferredRemote,
            )
            removeUnreviewedMatch(existing)
            stats.postAiRejected++
            return null
        }

        decisionFacade.upsert(
            preference.user,
            group,
            groupJobs,
            DecisionOutcome.AI_SCORED,
            aiScore = aiResult.score,
            inferredRemote = aiResult.inferredRemote,
        )

        return existing?.apply {
            aiRelevanceScore = aiResult.score
            aiReasoning = aiResult.reasoning
        } ?: UserJobGroupEntity(
            user = preference.user,
            group = group,
            aiRelevanceScore = aiResult.score,
            aiReasoning = aiResult.reasoning,
        )
    }

    private fun removeUnreviewedMatch(existing: UserJobGroupEntity?) {
        if (existing == null) return
        userJobGroupFacade.deleteByIdsAndUserIdAndStatus(
            listOf(existing.id),
            existing.user.id,
            UserJobStatus.NEW,
        )
    }

    private fun backfillRemoteIfNeeded(
        job: JobEntity,
        inferredRemote: Boolean,
    ) {
        if (job.remote != null) return
        job.remote = inferredRemote
        jobFacade.updateRemote(job.id, inferredRemote)
        logger.debug { "Backfilled remote=$inferredRemote for job '${job.title}'" }
    }

    private fun buildUserChains(preferences: List<UserPreferenceEntity>): Map<UUID, AiClientChain> =
        preferences
            .filter { it.matching.matchWithAi }
            .map { it.user.id }
            .distinct()
            .map { userId -> userId to buildUserChain(userId) }
            .filter { (_, chain) -> chain.links.isNotEmpty() }
            .toMap()

    private fun buildUserChain(userId: UUID): AiClientChain =
        try {
            val providers = userAiProviderService.chainFor(userId)
            val chain = chatClientFactory.createChain(providers, AiUseCase.SCORING)
            if (chain.links.isEmpty()) {
                logger.warn {
                    "User $userId has matchWithAi=true but no usable AI providers: ${chain.buildFailures.joinToString("; ")}"
                }
            }
            chain
        } catch (e: Exception) {
            logger.warn(e) { "Failed to build AI provider chain for user $userId" }
            AiClientChain(emptyList())
        }

    @Transactional
    fun rematch(since: Instant?): Int {
        val maxSince = Instant.now(clock).minus(MAX_REMATCH_PERIOD)
        val effectiveSince =
            when {
                since == null -> maxSince
                since.isBefore(maxSince) -> maxSince
                else -> since
            }
        val jobs = jobFacade.findMatchedSince(effectiveSince)
        if (jobs.isEmpty()) return 0

        jobFacade.resetMatchingState(jobs.map { it.id })

        logger.info { "Rematch queued: ${jobs.size} jobs reset (since=$effectiveSince)" }
        return jobs.size
    }

    private fun markMatched(jobs: List<JobEntity>) {
        jobFacade.updateMatchedAt(jobs.map { it.id }, Instant.now(clock))
    }

    private sealed interface MatchResult {
        val stats: MatchingStats

        data class Success(val jobs: List<JobEntity>, override val stats: MatchingStats) : MatchResult

        data class Failure(val jobs: List<JobEntity>, override val stats: MatchingStats) : MatchResult
    }

    companion object {
        private val MAX_REMATCH_PERIOD = Duration.ofDays(3)
        private const val COLD_ONLY_REASONING = "Cold filter match only — AI evaluation disabled"
        private const val GROUP_FANOUT_LIMIT = 5
    }
}
