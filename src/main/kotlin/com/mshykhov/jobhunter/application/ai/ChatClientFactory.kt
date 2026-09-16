package com.mshykhov.jobhunter.application.ai

import com.mshykhov.jobhunter.application.common.AiNotConfiguredException
import com.mshykhov.jobhunter.application.settings.AiProvider
import com.mshykhov.jobhunter.infrastructure.ai.AiProviderProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.retry.TransientAiException
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.retry.support.RetryTemplate
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

private val logger = KotlinLogging.logger {}

@Component
class ChatClientFactory(private val aiProviderProperties: AiProviderProperties, private val observationRegistry: ObservationRegistry) {
    private val retryTemplate: RetryTemplate =
        RetryTemplate
            .builder()
            .maxAttempts(aiProviderProperties.retryMaxAttempts)
            .fixedBackoff(aiProviderProperties.retryBackoff)
            .retryOn(TransientAiException::class.java)
            .build()

    private val restClientBuilder: RestClient.Builder =
        RestClient.builder().requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(aiProviderProperties.connectTimeout)
                setReadTimeout(aiProviderProperties.readTimeout)
            },
        )

    fun createForProvider(
        provider: UserAiProviderEntity,
        useCase: AiUseCase = AiUseCase.SCORING,
    ): ChatClient {
        val apiKey = resolveApiKey(provider)
        val builder = OpenAiApi.builder().apiKey(apiKey).restClientBuilder(restClientBuilder)
        aiProviderProperties.baseUrlFor(provider.provider)?.let { builder.baseUrl(it) }
        val model =
            OpenAiChatModel
                .builder()
                .openAiApi(builder.build())
                .defaultOptions(buildOptions(provider.provider, provider.modelId, useCase))
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build()
        return ChatClient.builder(model).build()
    }

    fun createChain(
        providers: List<UserAiProviderEntity>,
        useCase: AiUseCase = AiUseCase.SCORING,
    ): AiClientChain {
        val attempts =
            providers
                .filter { it.enabled }
                .sortedBy { it.priority }
                .map { provider -> provider to runCatching { createForProvider(provider, useCase) } }
        val links =
            attempts.mapNotNull { (provider, result) ->
                result.getOrNull()?.let { AiClientLink(provider.provider, provider.modelId, it) }
            }
        val buildFailures =
            attempts.mapNotNull { (provider, result) ->
                result.exceptionOrNull()?.let { e ->
                    logger.warn(e) { "Skipping AI provider ${provider.provider} at priority ${provider.priority}: ${e.message}" }
                    "${provider.provider}: ${e.message}"
                }
            }
        return AiClientChain(links, buildFailures)
    }

    private fun resolveApiKey(provider: UserAiProviderEntity): String =
        if (provider.provider.requiresApiKey) {
            provider.apiKey.ifBlank {
                throw AiNotConfiguredException(
                    "API key is missing for ${provider.provider.displayName} - please add one in settings.",
                )
            }
        } else if (aiProviderProperties.baseUrlFor(provider.provider) == null) {
            throw AiNotConfiguredException(
                "No base URL configured for ${provider.provider.displayName} - set ${baseUrlEnvVarFor(provider.provider)}.",
            )
        } else {
            DUMMY_API_KEY
        }

    private fun baseUrlEnvVarFor(provider: AiProvider): String =
        when (provider) {
            AiProvider.CODEX -> "AI_CODEX_BASE_URL"
            AiProvider.GROQ -> "AI_GROQ_BASE_URL"
            AiProvider.NVIDIA -> "AI_NVIDIA_BASE_URL"
            AiProvider.GEMINI -> "AI_GEMINI_BASE_URL"
            AiProvider.OPENAI -> "AI_OPENAI_BASE_URL"
        }

    private fun buildOptions(
        provider: AiProvider,
        modelId: String,
        useCase: AiUseCase,
    ): OpenAiChatOptions {
        val builder = OpenAiChatOptions.builder().model(modelId)
        if (provider == AiProvider.NVIDIA) {
            builder.extraBody(mapOf("chat_template_kwargs" to mapOf("enable_thinking" to false)))
        }
        if (isReasoningModel(modelId)) {
            builder.reasoningEffort(useCase.reasoningEffort).maxCompletionTokens(useCase.maxCompletionTokens)
        } else {
            builder.temperature(useCase.temperature).maxTokens(useCase.maxCompletionTokens)
        }
        return builder.build()
    }

    companion object {
        private const val DUMMY_API_KEY = "dummy"

        // OpenAI reasoning models that use reasoning_effort instead of temperature.
        // Non-OpenAI models (Claude, etc.) go through OpenAI-compatible API and receive temperature.
        internal fun isReasoningModel(modelId: String): Boolean =
            modelId.startsWith("gpt-5") ||
                modelId.startsWith("o1") ||
                modelId.startsWith("o3") ||
                modelId.startsWith("o4")
    }
}
