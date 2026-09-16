package com.mshykhov.jobhunter.infrastructure.ai

import com.mshykhov.jobhunter.application.settings.AiProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "jobhunter.ai.providers")
data class AiProviderProperties(
    val codexBaseUrl: String = "",
    val groqBaseUrl: String = "https://api.groq.com/openai",
    val nvidiaBaseUrl: String = "https://integrate.api.nvidia.com",
    val geminiBaseUrl: String = "",
    val retryMaxAttempts: Int = 2,
    val retryBackoff: Duration = Duration.ofSeconds(1),
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(60),
) {
    fun baseUrlFor(provider: AiProvider): String? =
        when (provider) {
            AiProvider.CODEX -> codexBaseUrl.takeIf { it.isNotBlank() }?.let(::normalizeBaseUrl)
            AiProvider.GROQ -> groqBaseUrl.takeIf { it.isNotBlank() }?.let(::normalizeBaseUrl)
            AiProvider.NVIDIA -> nvidiaBaseUrl.takeIf { it.isNotBlank() }?.let(::normalizeBaseUrl)
            AiProvider.GEMINI -> geminiBaseUrl.takeIf { it.isNotBlank() }?.let(::normalizeBaseUrl)
            AiProvider.OPENAI -> null
        }

    private fun normalizeBaseUrl(url: String): String = url.removeSuffix("/v1/").removeSuffix("/v1")
}
