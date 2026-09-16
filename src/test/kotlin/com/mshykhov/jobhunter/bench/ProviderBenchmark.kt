package com.mshykhov.jobhunter.bench

import com.mshykhov.jobhunter.application.ai.AiClientChain
import com.mshykhov.jobhunter.application.ai.AiClientLink
import com.mshykhov.jobhunter.application.ai.AiUseCase
import com.mshykhov.jobhunter.application.ai.ChatClientFactory
import com.mshykhov.jobhunter.application.ai.JobRelevanceEvaluator
import com.mshykhov.jobhunter.application.ai.UserAiProviderEntity
import com.mshykhov.jobhunter.application.common.AllProvidersFailedException
import com.mshykhov.jobhunter.application.job.Category
import com.mshykhov.jobhunter.application.job.JobSource
import com.mshykhov.jobhunter.application.preference.UserPreferenceEntity
import com.mshykhov.jobhunter.application.settings.AiModel
import com.mshykhov.jobhunter.application.settings.AiProvider
import com.mshykhov.jobhunter.infrastructure.ai.AiProviderProperties
import com.mshykhov.jobhunter.infrastructure.metrics.MatchingMetrics
import com.mshykhov.jobhunter.support.TestFixtures
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier

private const val GEMINI_DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
private const val GROQ_DEFAULT_BASE_URL = "https://api.groq.com/openai/"
private const val NVIDIA_DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/"
private const val REPORT_PATH = "build/bench/report.md"

private data class ProviderConfig(val provider: AiProvider, val apiKey: String, val models: List<String>, val skipReason: String?)

@Tag("bench")
class ProviderBenchmark {
    @Test
    fun `benchmark every configured provider against the labelled fixture`() {
        val (fixture, labels) = BenchFixtureLoader.load()
        val chatClientFactory =
            ChatClientFactory(
                AiProviderProperties(
                    codexBaseUrl = System.getenv("BENCH_CODEX_BASE_URL").orEmpty(),
                    groqBaseUrl = System.getenv("BENCH_GROQ_BASE_URL")?.takeIf { it.isNotBlank() } ?: GROQ_DEFAULT_BASE_URL,
                    nvidiaBaseUrl = System.getenv("BENCH_NVIDIA_BASE_URL")?.takeIf { it.isNotBlank() } ?: NVIDIA_DEFAULT_BASE_URL,
                    geminiBaseUrl = resolveGeminiBaseUrl(),
                ),
                ObservationRegistry.NOOP,
            )
        val evaluator = JobRelevanceEvaluator(MatchingMetrics(SimpleMeterRegistry(), Supplier { 0L }))
        val preferenceEntity =
            TestFixtures.userPreferenceEntity(
                about = fixture.preference.about,
                remoteOnly = fixture.preference.remoteOnly,
                categories = fixture.preference.categories.map { Category(it) }.toSet(),
                locations = fixture.preference.locations,
                excludedKeywords = fixture.preference.excludedKeywords,
            ).apply { matching.customPrompt = fixture.preference.customPrompt }

        val attempts = mutableListOf<BenchAttempt>()
        val skips = mutableListOf<ProviderSkip>()

        resolveProviderConfigs().forEach { config ->
            if (config.skipReason != null) {
                skips += ProviderSkip(config.provider, config.skipReason)
                return@forEach
            }
            config.models.forEach { modelId ->
                runModel(chatClientFactory, evaluator, config, modelId, fixture, preferenceEntity, attempts, skips)
            }
        }

        val report = renderMarkdown(fixture, labels, attempts, skips, Instant.now())
        val reportFile = File(REPORT_PATH)
        reportFile.parentFile.mkdirs()
        reportFile.writeText(report)
        println(report)
    }

    private fun runModel(
        chatClientFactory: ChatClientFactory,
        evaluator: JobRelevanceEvaluator,
        config: ProviderConfig,
        modelId: String,
        fixture: BenchFixture,
        preferenceEntity: UserPreferenceEntity,
        attempts: MutableList<BenchAttempt>,
        skips: MutableList<ProviderSkip>,
    ) {
        val providerEntity =
            UserAiProviderEntity(
                user = preferenceEntity.user,
                priority = 1,
                provider = config.provider,
                apiKey = config.apiKey,
                modelId = modelId,
                enabled = true,
            )
        val client = runCatching { chatClientFactory.createForProvider(providerEntity, AiUseCase.SCORING) }
        val chatClient = client.getOrNull()
        if (chatClient == null) {
            skips += ProviderSkip(config.provider, "$modelId: ${client.exceptionOrNull()?.message}")
            return
        }
        val chain = AiClientChain(listOf(AiClientLink(config.provider, modelId, chatClient)))
        fixture.jobs.forEach { job ->
            attempts += evaluateJob(evaluator, chain, config.provider, modelId, job, preferenceEntity)
        }
    }

    private fun evaluateJob(
        evaluator: JobRelevanceEvaluator,
        chain: AiClientChain,
        provider: AiProvider,
        modelId: String,
        job: BenchJob,
        preferenceEntity: UserPreferenceEntity,
    ): BenchAttempt {
        val jobEntity =
            TestFixtures.jobEntity(
                title = job.title,
                company = job.company,
                description = job.description,
                remote = job.remote,
                salary = job.salary,
                location = job.location,
                source = JobSource.DOU,
                url = "https://bench.local/${job.id}",
            )
        val startedAt = System.nanoTime()
        return try {
            val result = evaluator.evaluate(jobEntity, preferenceEntity, chain)
            BenchAttempt(
                provider = provider,
                model = modelId,
                jobId = job.id,
                latencyMs = elapsedMsSince(startedAt),
                score = result.score,
                reasoningLength = result.reasoning.length,
                outcome = "parsed",
                error = null,
            )
        } catch (e: AllProvidersFailedException) {
            BenchAttempt(
                provider = provider,
                model = modelId,
                jobId = job.id,
                latencyMs = elapsedMsSince(startedAt),
                score = null,
                reasoningLength = null,
                outcome = classifyFailure(e.message.orEmpty()),
                error = e.message,
            )
        }
    }
}

private fun resolveProviderConfigs(): List<ProviderConfig> =
    listOf(
        resolveCodex(),
        resolveKeyed(AiProvider.GROQ, "BENCH_GROQ_KEY", "BENCH_GROQ_MODELS"),
        resolveKeyed(AiProvider.NVIDIA, "BENCH_NVIDIA_KEY", "BENCH_NVIDIA_MODELS"),
        resolveKeyed(AiProvider.OPENAI, "BENCH_OPENAI_KEY", "BENCH_OPENAI_MODELS"),
        resolveKeyed(AiProvider.GEMINI, "BENCH_GEMINI_KEY", "BENCH_GEMINI_MODELS"),
    )

private fun resolveCodex(): ProviderConfig {
    val baseUrl = System.getenv("BENCH_CODEX_BASE_URL")
    return if (baseUrl.isNullOrBlank()) {
        ProviderConfig(AiProvider.CODEX, "", emptyList(), "BENCH_CODEX_BASE_URL is not set")
    } else {
        ProviderConfig(AiProvider.CODEX, "", modelsFor(AiProvider.CODEX, "BENCH_CODEX_MODELS"), null)
    }
}

private fun resolveKeyed(
    provider: AiProvider,
    keyEnvVar: String,
    modelsEnvVar: String,
): ProviderConfig {
    val apiKey = System.getenv(keyEnvVar)
    return if (apiKey.isNullOrBlank()) {
        ProviderConfig(provider, "", emptyList(), "$keyEnvVar is not set")
    } else {
        ProviderConfig(provider, apiKey, modelsFor(provider, modelsEnvVar), null)
    }
}

private fun modelsFor(
    provider: AiProvider,
    envVar: String,
): List<String> =
    System.getenv(envVar)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: AiModel.entries.filter { it.provider == provider }.map { it.id }

private fun resolveGeminiBaseUrl(): String = System.getenv("BENCH_GEMINI_BASE_URL")?.takeIf { it.isNotBlank() } ?: GEMINI_DEFAULT_BASE_URL

private fun elapsedMsSince(startedAtNanos: Long): Long = Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis()

private fun classifyFailure(message: String): String =
    when {
        message.contains("insufficient_quota", ignoreCase = true) || message.contains(" 429", ignoreCase = true) -> "quota"
        message.contains(" 401", ignoreCase = true) || message.contains(" 403", ignoreCase = true) -> "auth"
        message.contains("could not be parsed", ignoreCase = true) -> "parse_error"
        message.contains("timeout", ignoreCase = true) || message.contains("Connect", ignoreCase = true) -> "transient"
        else -> "unavailable"
    }
