package com.mshykhov.jobhunter.application.matching

import com.mshykhov.jobhunter.application.ai.AiClientChain
import com.mshykhov.jobhunter.application.ai.AiClientLink
import com.mshykhov.jobhunter.application.ai.AiUseCase
import com.mshykhov.jobhunter.application.ai.ChatClientFactory
import com.mshykhov.jobhunter.application.ai.JobRelevanceEvaluator
import com.mshykhov.jobhunter.application.ai.UserAiProviderService
import com.mshykhov.jobhunter.application.ai.dto.JobRelevanceResult
import com.mshykhov.jobhunter.application.common.AllProvidersFailedException
import com.mshykhov.jobhunter.application.job.JobEntity
import com.mshykhov.jobhunter.application.job.JobFacade
import com.mshykhov.jobhunter.application.job.JobGroupEntity
import com.mshykhov.jobhunter.application.job.JobGroupKeyComputer
import com.mshykhov.jobhunter.application.job.JobSource
import com.mshykhov.jobhunter.application.preference.MatchingPreferences
import com.mshykhov.jobhunter.application.preference.SearchPreferences
import com.mshykhov.jobhunter.application.preference.TelegramPreferences
import com.mshykhov.jobhunter.application.preference.UserPreferenceEntity
import com.mshykhov.jobhunter.application.preference.UserPreferenceFacade
import com.mshykhov.jobhunter.application.settings.AiProvider
import com.mshykhov.jobhunter.application.statistics.DecisionOutcome
import com.mshykhov.jobhunter.application.statistics.UserJobGroupDecisionFacade
import com.mshykhov.jobhunter.application.user.UserEntity
import com.mshykhov.jobhunter.application.userjob.UserJobGroupEntity
import com.mshykhov.jobhunter.application.userjob.UserJobGroupFacade
import com.mshykhov.jobhunter.application.userjob.UserJobStatus
import com.mshykhov.jobhunter.infrastructure.ai.AiProperties
import com.mshykhov.jobhunter.infrastructure.matching.MatchingProperties
import com.mshykhov.jobhunter.support.TestFixtures
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class JobMatchingServiceTest {
    private val jobFacade = mockk<JobFacade>()
    private val userPreferenceFacade = mockk<UserPreferenceFacade>()
    private val userJobGroupFacade = mockk<UserJobGroupFacade>()
    private val decisionFacade = mockk<UserJobGroupDecisionFacade>(relaxed = true)
    private val userAiProviderService = mockk<UserAiProviderService>()
    private val jobRelevanceEvaluator = mockk<JobRelevanceEvaluator>()
    private val chatClientFactory = mockk<ChatClientFactory>()
    private val aiProperties = AiProperties(matching = AiProperties.MatchingProperties(concurrency = 2))
    private val matchingProperties = MatchingProperties(batchSize = 200)
    private val clock = Clock.fixed(Instant.parse("2026-03-09T12:00:00Z"), ZoneOffset.UTC)

    private val service =
        JobMatchingService(
            jobFacade = jobFacade,
            userPreferenceFacade = userPreferenceFacade,
            userJobGroupFacade = userJobGroupFacade,
            decisionFacade = decisionFacade,
            userAiProviderService = userAiProviderService,
            jobRelevanceEvaluator = jobRelevanceEvaluator,
            chatClientFactory = chatClientFactory,
            aiProperties = aiProperties,
            matchingProperties = matchingProperties,
            clock = clock,
        )

    private fun chainOf(
        client: ChatClient,
        modelId: String = "gpt-4o-mini",
    ): AiClientChain = AiClientChain(listOf(AiClientLink(AiProvider.OPENAI, modelId, client)))

    @Nested
    inner class ProcessUnmatchedJobs {
        @Test
        fun `should do nothing when no unmatched jobs`() {
            every { jobFacade.findUnmatched(200, 5) } returns emptyList()

            service.processUnmatchedJobs()

            verify(exactly = 0) { userPreferenceFacade.findAll() }
        }

        @Test
        fun `should request unmatched jobs bounded by configured batch size`() {
            every { jobFacade.findUnmatched(200, 5) } returns emptyList()

            service.processUnmatchedJobs()

            verify { jobFacade.findUnmatched(200, 5) }
        }

        @Test
        fun `should mark jobs as matched when no user preferences exist`() {
            val job = testJob()
            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns emptyList()
            every { jobFacade.updateMatchedAt(listOf(job.id), any()) } just Runs

            service.processUnmatchedJobs()

            verify { jobFacade.updateMatchedAt(listOf(job.id), any()) }
        }

        @Test
        fun `should fail matching instead of falling back to cold-only when user has matchWithAi enabled but no AI providers configured`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group)
            val preference = testPreference(user, matchWithAi = true)

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns emptyList()
            every { chatClientFactory.createChain(emptyList(), AiUseCase.SCORING) } returns AiClientChain(emptyList())
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()

            val outcome = service.processUnmatchedJobs()

            assertEquals(MatchingOutcome.AI_UNAVAILABLE, outcome)
            verify(exactly = 0) { userJobGroupFacade.saveAll(any()) }
            verify(exactly = 0) { jobFacade.updateMatchedAt(any(), any()) }
        }

        @Test
        fun `should evaluate with AI and save result when user has AI providers configured`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group)
            val preference = testPreference(user, matchWithAi = true)
            val provider = TestFixtures.userAiProviderEntity(user = user, modelId = "gpt-4o-mini")
            val chatClient = mockk<ChatClient>()
            val savedSlot = slot<List<UserJobGroupEntity>>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns listOf(provider)
            every { chatClientFactory.createChain(listOf(provider), AiUseCase.SCORING) } returns chainOf(chatClient)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { jobRelevanceEvaluator.evaluate(job, preference, chainOf(chatClient)) } returns
                JobRelevanceResult(score = 85, reasoning = "Strong Kotlin match", inferredRemote = true)
            every { userJobGroupFacade.saveAll(capture(savedSlot)) } answers { savedSlot.captured }
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs
            every { jobFacade.updateRemote(job.id, true) } just Runs

            service.processUnmatchedJobs()

            assertEquals(1, savedSlot.captured.size)
            assertEquals(85, savedSlot.captured[0].aiRelevanceScore)
            assertEquals("Strong Kotlin match", savedSlot.captured[0].aiReasoning)
            verify { decisionFacade.upsert(user, group, listOf(job), DecisionOutcome.AI_SCORED, null, 85, true) }
        }

        @Test
        fun `should reject job post-AI when remoteOnly and inferredRemote is false`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group, remote = null)
            val preference = testPreference(user, matchWithAi = true, remoteOnly = true)
            val provider = TestFixtures.userAiProviderEntity(user = user, modelId = "gpt-4o-mini")
            val chatClient = mockk<ChatClient>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns listOf(provider)
            every { chatClientFactory.createChain(listOf(provider), AiUseCase.SCORING) } returns chainOf(chatClient)
            val existing = TestFixtures.userJobGroupEntity(user = user, group = group)
            every { userJobGroupFacade.findByGroupId(group.id) } returns listOf(existing)
            every {
                userJobGroupFacade.deleteByIdsAndUserIdAndStatus(listOf(existing.id), user.id, UserJobStatus.NEW)
            } returns 1
            every { jobRelevanceEvaluator.evaluate(job, preference, chainOf(chatClient)) } returns
                JobRelevanceResult(score = 70, reasoning = "Match but not remote", inferredRemote = false)
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs
            every { jobFacade.updateRemote(job.id, false) } just Runs

            service.processUnmatchedJobs()

            verify(exactly = 0) { userJobGroupFacade.saveAll(any()) }
            verify {
                userJobGroupFacade.deleteByIdsAndUserIdAndStatus(listOf(existing.id), user.id, UserJobStatus.NEW)
            }
            verify { decisionFacade.upsert(user, group, listOf(job), DecisionOutcome.AI_REJECTED_REMOTE, null, 70, false) }
        }

        @Test
        fun `should not mark jobs as matched when AI evaluation fails`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group)
            val preference = testPreference(user, matchWithAi = true)
            val provider = TestFixtures.userAiProviderEntity(user = user, modelId = "gpt-4o-mini")
            val chatClient = mockk<ChatClient>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns listOf(provider)
            every { chatClientFactory.createChain(listOf(provider), AiUseCase.SCORING) } returns chainOf(chatClient)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { jobRelevanceEvaluator.evaluate(job, preference, chainOf(chatClient)) } throws
                RuntimeException("429 insufficient_quota")

            val outcome = service.processUnmatchedJobs()

            assertEquals(MatchingOutcome.AI_UNAVAILABLE, outcome)
            verify(exactly = 0) { jobFacade.updateMatchedAt(any(), any()) }
            verify(exactly = 0) { decisionFacade.upsert(any(), any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `should report completed when at least one AI evaluation succeeds`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group)
            val preference = testPreference(user, matchWithAi = true)
            val provider = TestFixtures.userAiProviderEntity(user = user, modelId = "gpt-4o-mini")
            val chatClient = mockk<ChatClient>()
            val savedSlot = slot<List<UserJobGroupEntity>>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns listOf(provider)
            every { chatClientFactory.createChain(listOf(provider), AiUseCase.SCORING) } returns chainOf(chatClient)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { jobRelevanceEvaluator.evaluate(job, preference, chainOf(chatClient)) } returns
                JobRelevanceResult(score = 85, reasoning = "Strong Kotlin match", inferredRemote = true)
            every { userJobGroupFacade.saveAll(capture(savedSlot)) } answers { savedSlot.captured }
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs

            val outcome = service.processUnmatchedJobs()

            assertEquals(MatchingOutcome.COMPLETED, outcome)
            verify { jobFacade.updateMatchedAt(listOf(job.id), any()) }
        }

        @Test
        fun `should report idle when there is nothing to match`() {
            every { jobFacade.findUnmatched(200, 5) } returns emptyList()

            assertEquals(MatchingOutcome.IDLE, service.processUnmatchedJobs())
        }

        @Test
        fun `should skip cold-filtered jobs and not call AI`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group, source = JobSource.DJINNI)
            val preference = testPreference(user, disabledSources = listOf(JobSource.DJINNI))

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs

            service.processUnmatchedJobs()

            verify(exactly = 0) { jobRelevanceEvaluator.evaluate(any(), any(), any()) }
            verify(exactly = 0) { userJobGroupFacade.saveAll(any()) }
            verify { decisionFacade.upsert(user, group, listOf(job), DecisionOutcome.COLD_REJECTED, "source", null, null) }
        }

        @Test
        fun `should remove an existing new match when a cold filter rejects it`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group, source = JobSource.DJINNI)
            val preference = testPreference(user, disabledSources = listOf(JobSource.DJINNI))
            val existing = TestFixtures.userJobGroupEntity(user = user, group = group)

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userJobGroupFacade.findByGroupId(group.id) } returns listOf(existing)
            every {
                userJobGroupFacade.deleteByIdsAndUserIdAndStatus(listOf(existing.id), user.id, UserJobStatus.NEW)
            } returns 1
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs

            service.processUnmatchedJobs()

            verify {
                userJobGroupFacade.deleteByIdsAndUserIdAndStatus(listOf(existing.id), user.id, UserJobStatus.NEW)
            }
        }

        @Test
        fun `should guard reviewed matches from cold-filter cleanup`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group, source = JobSource.DJINNI)
            val preference = testPreference(user, disabledSources = listOf(JobSource.DJINNI))
            val existing =
                TestFixtures.userJobGroupEntity(user = user, group = group, status = UserJobStatus.APPLIED)

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userJobGroupFacade.findByGroupId(group.id) } returns listOf(existing)
            every {
                userJobGroupFacade.deleteByIdsAndUserIdAndStatus(listOf(existing.id), user.id, UserJobStatus.NEW)
            } returns 0
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs

            service.processUnmatchedJobs()

            verify {
                userJobGroupFacade.deleteByIdsAndUserIdAndStatus(listOf(existing.id), user.id, UserJobStatus.NEW)
            }
        }
    }

    @Nested
    inner class AiClientResilience {
        @Test
        fun `should fail only the broken user without affecting a healthy user in the same group`() {
            val brokenUser = UserEntity(auth0Sub = "user-broken")
            val healthyUser = UserEntity(auth0Sub = "user-healthy")
            val group = testGroup()
            val job = testJob(group = group)
            val brokenPreference = testPreference(brokenUser, matchWithAi = true)
            val healthyPreference = testPreference(healthyUser, matchWithAi = true)
            val healthyProvider = TestFixtures.userAiProviderEntity(user = healthyUser, modelId = "gpt-4o-mini")
            val chatClient = mockk<ChatClient>()
            val savedSlot = slot<List<UserJobGroupEntity>>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(brokenPreference, healthyPreference)
            every { userAiProviderService.chainFor(brokenUser.id) } throws RuntimeException("connection reset")
            every { userAiProviderService.chainFor(healthyUser.id) } returns listOf(healthyProvider)
            every { chatClientFactory.createChain(listOf(healthyProvider), AiUseCase.SCORING) } returns chainOf(chatClient)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { jobRelevanceEvaluator.evaluate(job, healthyPreference, chainOf(chatClient)) } returns
                JobRelevanceResult(score = 80, reasoning = "Good match", inferredRemote = true)
            every { userJobGroupFacade.saveAll(capture(savedSlot)) } answers { savedSlot.captured }
            every { jobFacade.updateRemote(job.id, true) } just Runs
            every { jobFacade.incrementMatchAttempts(listOf(job.id)) } just Runs

            service.processUnmatchedJobs()

            assertEquals(1, savedSlot.captured.size)
            assertEquals(healthyUser.id, savedSlot.captured[0].user.id)
            assertEquals("Good match", savedSlot.captured[0].aiReasoning)
        }

        @Test
        fun `should fail the user instead of falling back to cold-only when the whole chain fails to build any usable link`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group)
            val preference = testPreference(user, matchWithAi = true)
            val provider = TestFixtures.userAiProviderEntity(user = user, modelId = "gpt-4o-mini")

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns listOf(provider)
            every { chatClientFactory.createChain(listOf(provider), AiUseCase.SCORING) } returns
                AiClientChain(emptyList(), listOf("OPENAI: API key is missing"))
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()

            val outcome = service.processUnmatchedJobs()

            assertEquals(MatchingOutcome.AI_UNAVAILABLE, outcome)
            verify(exactly = 0) { jobRelevanceEvaluator.evaluate(any(), any(), any()) }
            verify(exactly = 0) { userJobGroupFacade.saveAll(any()) }
            verify(exactly = 0) { jobFacade.updateMatchedAt(any(), any()) }
        }
    }

    @Nested
    inner class ChainExhaustion {
        @Test
        fun `should treat an exhausted provider chain exactly like a single failed provider`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group)
            val preference = testPreference(user, matchWithAi = true)
            val provider = TestFixtures.userAiProviderEntity(user = user, modelId = "gpt-4o-mini")
            val chatClient = mockk<ChatClient>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns listOf(provider)
            every { chatClientFactory.createChain(listOf(provider), AiUseCase.SCORING) } returns chainOf(chatClient)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { jobRelevanceEvaluator.evaluate(job, preference, chainOf(chatClient)) } throws
                AllProvidersFailedException("All AI providers failed: OPENAI: insufficient_quota")

            val outcome = service.processUnmatchedJobs()

            assertEquals(MatchingOutcome.AI_UNAVAILABLE, outcome)
            verify(exactly = 0) { jobFacade.updateMatchedAt(any(), any()) }
            verify(exactly = 0) { jobFacade.incrementMatchAttempts(any()) }
            verify(exactly = 0) { userJobGroupFacade.saveAll(any()) }
        }
    }

    @Nested
    inner class MatchAttempts {
        @Test
        fun `should increment match attempts for a group that fails while another group succeeds`() {
            val user = UserEntity(auth0Sub = "user-1")
            val okGroup = testGroup(title = "OK Group")
            val okJob = testJob(group = okGroup, title = "OK Group")
            val failGroup = testGroup(title = "Failing Group")
            val failJob = testJob(group = failGroup, title = "Failing Group")
            val preference = testPreference(user, matchWithAi = true)
            val provider = TestFixtures.userAiProviderEntity(user = user, modelId = "gpt-4o-mini")
            val chatClient = mockk<ChatClient>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(okJob, failJob)
            every { jobFacade.findByGroupIds(listOf(okGroup.id, failGroup.id), 1000, 5) } returns listOf(okJob, failJob)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns listOf(provider)
            every { chatClientFactory.createChain(listOf(provider), AiUseCase.SCORING) } returns chainOf(chatClient)
            every { userJobGroupFacade.findByGroupId(okGroup.id) } returns emptyList()
            every { userJobGroupFacade.findByGroupId(failGroup.id) } returns emptyList()
            every { jobRelevanceEvaluator.evaluate(okJob, preference, chainOf(chatClient)) } returns
                JobRelevanceResult(score = 85, reasoning = "Great match", inferredRemote = true)
            every { jobRelevanceEvaluator.evaluate(failJob, preference, chainOf(chatClient)) } throws
                RuntimeException("context length exceeded")
            every { userJobGroupFacade.saveAll(any()) } answers { firstArg() }
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs
            every { jobFacade.updateRemote(okJob.id, true) } just Runs
            every { jobFacade.incrementMatchAttempts(listOf(failJob.id)) } just Runs

            service.processUnmatchedJobs()

            verify { jobFacade.incrementMatchAttempts(listOf(failJob.id)) }
        }

        @Test
        fun `should not increment match attempts when every group fails because the AI provider itself is down`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group)
            val preference = testPreference(user, matchWithAi = true)
            val provider = TestFixtures.userAiProviderEntity(user = user, modelId = "gpt-4o-mini")
            val chatClient = mockk<ChatClient>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns listOf(provider)
            every { chatClientFactory.createChain(listOf(provider), AiUseCase.SCORING) } returns chainOf(chatClient)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { jobRelevanceEvaluator.evaluate(job, preference, chainOf(chatClient)) } throws
                RuntimeException("connection refused")

            service.processUnmatchedJobs()

            verify(exactly = 0) { jobFacade.incrementMatchAttempts(any()) }
        }
    }

    @Nested
    inner class GroupMatching {
        @Test
        fun `should select job with longest description as representative`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val shortJob =
                testJob(
                    group = group,
                    title = "Senior Kotlin Developer",
                ).apply { description = "Short" }
            val longJob =
                testJob(
                    group = group,
                    title = "Senior Kotlin Developer",
                ).apply { description = "This is a much longer description for the Kotlin developer position" }
            val preference = testPreference(user, matchWithAi = true)
            val provider = TestFixtures.userAiProviderEntity(user = user, modelId = "gpt-4o-mini")
            val chatClient = mockk<ChatClient>()
            val savedSlot = slot<List<UserJobGroupEntity>>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(shortJob, longJob)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(shortJob, longJob)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns listOf(provider)
            every { chatClientFactory.createChain(listOf(provider), AiUseCase.SCORING) } returns chainOf(chatClient)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { jobRelevanceEvaluator.evaluate(longJob, preference, chainOf(chatClient)) } returns
                JobRelevanceResult(score = 90, reasoning = "Great match", inferredRemote = true)
            every { userJobGroupFacade.saveAll(capture(savedSlot)) } answers { savedSlot.captured }
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs
            every { jobFacade.updateRemote(longJob.id, true) } just Runs

            service.processUnmatchedJobs()

            verify { jobRelevanceEvaluator.evaluate(longJob, preference, chainOf(chatClient)) }
            verify(exactly = 0) { jobRelevanceEvaluator.evaluate(shortJob, any(), any()) }
        }

        @Test
        fun `should match one group to multiple users`() {
            val user1 = UserEntity(auth0Sub = "user-1")
            val user2 = UserEntity(auth0Sub = "user-2")
            val group = testGroup()
            val job = testJob(group = group)
            val preference1 = testPreference(user1, matchWithAi = false)
            val preference2 = testPreference(user2, matchWithAi = false)
            val savedSlot = slot<List<UserJobGroupEntity>>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference1, preference2)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { userJobGroupFacade.saveAll(capture(savedSlot)) } answers { savedSlot.captured }
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs

            service.processUnmatchedJobs()

            assertEquals(2, savedSlot.captured.size)
            val userIds = savedSlot.captured.map { it.user.id }.toSet()
            assertEquals(setOf(user1.id, user2.id), userIds)
        }

        @Test
        fun `should skip user who already has this group matched`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group)
            val preference = testPreference(user, matchWithAi = false)
            val existingUserJobGroup =
                UserJobGroupEntity(
                    user = user,
                    group = group,
                    aiRelevanceScore = 50,
                    aiReasoning = "Old reasoning",
                )
            val savedSlot = slot<List<UserJobGroupEntity>>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userJobGroupFacade.findByGroupId(group.id) } returns listOf(existingUserJobGroup)
            every { userJobGroupFacade.saveAll(capture(savedSlot)) } answers { savedSlot.captured }
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs

            service.processUnmatchedJobs()

            assertEquals(1, savedSlot.captured.size)
            assertEquals(0, savedSlot.captured[0].aiRelevanceScore)
            assertEquals("Cold filter match only — AI evaluation disabled", savedSlot.captured[0].aiReasoning)
            verify { decisionFacade.upsert(user, group, listOf(job), DecisionOutcome.COLD_ONLY, null, null, null) }
        }

        @Test
        fun `should mark all jobs in group as matched even when no preferences pass`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job1 = testJob(group = group, source = JobSource.DJINNI)
            val job2 =
                JobEntity(
                    title = "Senior Kotlin Developer",
                    group = group,
                    url = "https://example.com/test-job-2",
                    description = "Another listing",
                    source = JobSource.DJINNI,
                    remote = true,
                    lastSeenAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            val preference = testPreference(user, disabledSources = listOf(JobSource.DJINNI))

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job1, job2)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job1, job2)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { jobFacade.updateMatchedAt(listOf(job1.id, job2.id), any()) } just Runs

            service.processUnmatchedJobs()

            verify { jobFacade.updateMatchedAt(listOf(job1.id, job2.id), any()) }
            verify(exactly = 0) { userJobGroupFacade.saveAll(any()) }
        }

        @Test
        fun `should refetch the full group when the batch window splits it across the boundary`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val windowedJob = testJob(group = group).apply { description = "Short description" }
            val laterJob = testJob(group = group).apply { description = "A considerably longer description for the same group" }
            val preference = testPreference(user, matchWithAi = false)

            every { jobFacade.findUnmatched(200, 5) } returns listOf(windowedJob)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(windowedJob, laterJob)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { userJobGroupFacade.saveAll(any()) } answers { firstArg() }
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs

            service.processUnmatchedJobs()

            verify { jobFacade.updateMatchedAt(listOf(windowedJob.id, laterJob.id), any()) }
        }

        @Test
        fun `should not pull a job at the match attempts cap back in through its group refetch`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val eligibleJob = testJob(group = group)
            val preference = testPreference(user, matchWithAi = false)

            every { jobFacade.findUnmatched(200, 5) } returns listOf(eligibleJob)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(eligibleJob)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { userJobGroupFacade.saveAll(any()) } answers { firstArg() }
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs

            service.processUnmatchedJobs()

            verify { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) }
            verify { jobFacade.updateMatchedAt(listOf(eligibleJob.id), any()) }
        }

        @Test
        fun `should log a warning and keep processing when the group refetch hits the fan-out limit`() {
            val truncatingProperties = MatchingProperties(batchSize = 1, maxAttempts = 5)
            val truncatingService =
                JobMatchingService(
                    jobFacade = jobFacade,
                    userPreferenceFacade = userPreferenceFacade,
                    userJobGroupFacade = userJobGroupFacade,
                    decisionFacade = decisionFacade,
                    userAiProviderService = userAiProviderService,
                    jobRelevanceEvaluator = jobRelevanceEvaluator,
                    chatClientFactory = chatClientFactory,
                    aiProperties = aiProperties,
                    matchingProperties = truncatingProperties,
                    clock = clock,
                )
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val pageJob = testJob(group = group)
            val fannedOutJobs = List(5) { testJob(group = group) }
            val preference = testPreference(user, matchWithAi = false)

            every { jobFacade.findUnmatched(1, 5) } returns listOf(pageJob)
            every { jobFacade.findByGroupIds(listOf(group.id), 5, 5) } returns fannedOutJobs
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { userJobGroupFacade.saveAll(any()) } answers { firstArg() }
            every { jobFacade.updateMatchedAt(any(), any()) } just Runs

            truncatingService.processUnmatchedJobs()

            verify { jobFacade.findByGroupIds(listOf(group.id), 5, 5) }
            verify { jobFacade.updateMatchedAt(fannedOutJobs.map { it.id }, any()) }
        }

        @Test
        fun `should handle AI evaluation failure gracefully and leave group unmatched`() {
            val user = UserEntity(auth0Sub = "user-1")
            val group = testGroup()
            val job = testJob(group = group)
            val preference = testPreference(user, matchWithAi = true)
            val provider = TestFixtures.userAiProviderEntity(user = user, modelId = "gpt-4o-mini")
            val chatClient = mockk<ChatClient>()

            every { jobFacade.findUnmatched(200, 5) } returns listOf(job)
            every { jobFacade.findByGroupIds(listOf(group.id), 1000, 5) } returns listOf(job)
            every { userPreferenceFacade.findAll() } returns listOf(preference)
            every { userAiProviderService.chainFor(user.id) } returns listOf(provider)
            every { chatClientFactory.createChain(listOf(provider), AiUseCase.SCORING) } returns chainOf(chatClient)
            every { userJobGroupFacade.findByGroupId(group.id) } returns emptyList()
            every { jobRelevanceEvaluator.evaluate(job, preference, chainOf(chatClient)) } throws RuntimeException("API error")

            service.processUnmatchedJobs()

            verify(exactly = 0) { userJobGroupFacade.saveAll(any()) }
            verify(exactly = 0) { jobFacade.updateMatchedAt(any(), any()) }
        }
    }

    @Nested
    inner class Rematch {
        @Test
        fun `should reset matchedAt for jobs matched since given time`() {
            val job = testJob()
            val since = Instant.parse("2026-03-08T00:00:00Z")

            every { jobFacade.findMatchedSince(since) } returns listOf(job)
            every { jobFacade.resetMatchingState(listOf(job.id)) } just Runs

            val count = service.rematch(since)

            assertEquals(1, count)
            verify { jobFacade.resetMatchingState(listOf(job.id)) }
        }

        @Test
        fun `should clamp since to max 3 days ago`() {
            val veryOldSince = Instant.parse("2026-01-01T00:00:00Z")
            val expectedSince = Instant.parse("2026-03-06T12:00:00Z")

            every { jobFacade.findMatchedSince(expectedSince) } returns emptyList()

            val count = service.rematch(veryOldSince)

            assertEquals(0, count)
            verify { jobFacade.findMatchedSince(expectedSince) }
        }

        @Test
        fun `should return zero when no jobs to rematch`() {
            every { jobFacade.findMatchedSince(any()) } returns emptyList()

            val count = service.rematch(null)

            assertEquals(0, count)
            verify(exactly = 0) { jobFacade.resetMatchingState(any()) }
        }
    }

    private fun testGroup(
        title: String = "Senior Kotlin Developer",
        company: String? = null,
    ): JobGroupEntity =
        JobGroupEntity(
            groupKey = JobGroupKeyComputer.compute(title, company),
            title = title,
            company = company,
        )

    private fun testJob(
        title: String = "Senior Kotlin Developer",
        source: JobSource = JobSource.DOU,
        remote: Boolean? = true,
        group: JobGroupEntity = testGroup(title),
    ): JobEntity =
        JobEntity(
            title = title,
            group = group,
            url = "https://example.com/test-job",
            description = "Looking for a Kotlin developer with Spring experience",
            source = source,
            remote = remote,
            lastSeenAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun testPreference(
        user: UserEntity,
        matchWithAi: Boolean = false,
        remoteOnly: Boolean = false,
        disabledSources: List<JobSource> = emptyList(),
    ): UserPreferenceEntity =
        UserPreferenceEntity(
            user = user,
            search =
            SearchPreferences(
                remoteOnly = remoteOnly,
                disabledSources = disabledSources,
            ),
            matching =
            MatchingPreferences(
                matchWithAi = matchWithAi,
            ),
            telegram = TelegramPreferences(),
        )
}
