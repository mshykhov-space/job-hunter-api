package com.mshykhov.jobhunter.application.ai

import com.mshykhov.jobhunter.application.ai.dto.NormalizedPreferences
import com.mshykhov.jobhunter.application.common.ServiceUnavailableException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.entity
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class PreferenceNormalizer {
    fun normalize(
        rawInput: String,
        chatClient: ChatClient,
    ): NormalizedPreferences {
        logger.info { "Normalizing preferences, input length: ${rawInput.length}" }
        return try {
            val result =
                chatClient
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(rawInput)
                    .call()
                    .entity<NormalizedPreferences>()
            logger.info {
                "Normalization complete: ${result.categories.size} categories, " +
                    "${result.excludedKeywords.size} excluded keywords"
            }
            result
        } catch (e: Exception) {
            logger.error(e) { "AI normalization failed" }
            throw ServiceUnavailableException("AI normalization failed: ${e.message}")
        }
    }
}

private val SYSTEM_PROMPT =
    """
    You are a job search preferences analyzer. Extract structured data from the user's free-text input.

    Extract the following:
    - categories: short job-search terms for the user's target roles and relevant core technologies.
      For nontechnical careers, use role titles such as administrative assistant, operations assistant,
      virtual assistant, customer support, or sales coordinator based on the user's experience and goals.
      General office and communication tools (Excel, Google Workspace, Notion, Slack, ChatGPT) are skills,
      not target roles; do not use them as categories unless the user explicitly seeks a specialist role in that tool.
      For software careers, preserve relevant core technologies such as kotlin, java, javascript, python,
      go, rust, or typescript. Do not assume every user works in software.
    - excludedKeywords: technologies or domains the user wants to avoid
    - locations: countries or regions to search jobs in (e.g. Ukraine, United States, Worldwide). Empty means no preference (use default).
    - remoteOnly: whether user wants only remote positions (true/false)
    - disabledSources: job source names the user explicitly wants to exclude from search. Empty by default (all sources active). Only add a source if the user explicitly says they don't want it.
    """.trimIndent()
