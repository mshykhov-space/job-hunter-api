package com.mshykhov.jobhunter.application.ai

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreferenceNormalizerTest {
    @Test
    fun `uses a role-aware request and decodes nontechnical search terms`() {
        val model = mockk<ChatModel>(relaxed = true)
        val prompt = slot<Prompt>()
        val about = "Administrative and operations support, customer service, Excel and Slack. Remote only."
        every { model.call(capture(prompt)) } returns
            ChatResponse(
                listOf(
                    Generation(
                        AssistantMessage(
                            """
                            {"categories":["administrative assistant","operations assistant","customer support"],
                             "excludedKeywords":[],"locations":[],"remoteOnly":true,"disabledSources":[]}
                            """.trimIndent(),
                        ),
                    ),
                ),
            )

        val result = PreferenceNormalizer().normalize(about, ChatClient.create(model))

        assertEquals(listOf("administrative assistant", "operations assistant", "customer support"), result.categories)
        assertTrue(result.remoteOnly)
        assertTrue(prompt.captured.systemMessage.text.orEmpty().contains("nontechnical"))
        assertTrue(prompt.captured.systemMessage.text.orEmpty().contains("not target roles"))
        assertTrue(prompt.captured.userMessage.text.orEmpty().startsWith(about))
    }
}
