package com.mshykhov.jobhunter.application.ai

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunter.application.common.AiNotConfiguredException
import com.mshykhov.jobhunter.application.settings.AiProvider
import com.mshykhov.jobhunter.infrastructure.ai.AiProviderProperties
import com.mshykhov.jobhunter.support.TestFixtures
import com.sun.net.httpserver.HttpServer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.ai.chat.observation.ChatModelObservationContext
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val CODEX_BASE_URL = "http://cli-proxy-api-prd.cli-proxy-api-prd.svc:8317/v1"
private const val UNREACHABLE_BASE_URL = "http://localhost:1"

class ChatClientFactoryTest {
    private val factory = ChatClientFactory(AiProviderProperties(), ObservationRegistry.NOOP)

    @Nested
    inner class CreateForProvider {
        @Test
        fun `should build client for a CODEX row without an API key`() {
            val factory = ChatClientFactory(AiProviderProperties(codexBaseUrl = CODEX_BASE_URL), ObservationRegistry.NOOP)
            val provider =
                TestFixtures.userAiProviderEntity(provider = AiProvider.CODEX, apiKey = "", modelId = "gpt-5.6-luna")

            val client = factory.createForProvider(provider)

            assertNotNull(client)
        }

        @Test
        fun `should throw AiNotConfiguredException when CODEX has no configured base url`() {
            val provider =
                TestFixtures.userAiProviderEntity(provider = AiProvider.CODEX, apiKey = "", modelId = "gpt-5.6-luna")

            val exception = assertThrows<AiNotConfiguredException> { factory.createForProvider(provider) }

            assertTrue(exception.message.orEmpty().contains("AI_CODEX_BASE_URL"))
        }

        @Test
        fun `should throw AiNotConfiguredException when OPENAI row has a blank key`() {
            val provider =
                TestFixtures.userAiProviderEntity(provider = AiProvider.OPENAI, apiKey = "", modelId = "gpt-4o-mini")

            assertThrows<AiNotConfiguredException> { factory.createForProvider(provider) }
        }

        @Test
        fun `should build clients for Groq and NVIDIA rows`() {
            val groq = TestFixtures.userAiProviderEntity(provider = AiProvider.GROQ, apiKey = "gsk-test", modelId = "openai/gpt-oss-120b")
            val nvidia =
                TestFixtures.userAiProviderEntity(
                    provider = AiProvider.NVIDIA,
                    apiKey = "nvapi-test",
                    modelId = "nvidia/nemotron-3.5-lightning-30b-a3b",
                )

            assertNotNull(factory.createForProvider(groq))
            assertNotNull(factory.createForProvider(nvidia))
        }

        @ParameterizedTest
        @ValueSource(strings = ["gpt-5-nano", "gpt-5", "o1-mini", "o3-mini", "o4-mini"])
        fun `should create client for reasoning models without error`(modelId: String) {
            val provider = TestFixtures.userAiProviderEntity(provider = AiProvider.OPENAI, apiKey = "test-key", modelId = modelId)

            val client = factory.createForProvider(provider, AiUseCase.OUTREACH)

            assertNotNull(client)
        }

        @ParameterizedTest
        @ValueSource(strings = ["gpt-4o-mini", "gpt-4o", "claude-haiku", "claude-sonnet"])
        fun `should create client for standard models without error`(modelId: String) {
            val provider = TestFixtures.userAiProviderEntity(provider = AiProvider.OPENAI, apiKey = "test-key", modelId = modelId)

            val client = factory.createForProvider(provider, AiUseCase.SCORING)

            assertNotNull(client)
        }

        @Test
        fun `should treat dotted gpt-5 point releases as reasoning models`() {
            assertTrue(ChatClientFactory.isReasoningModel("gpt-5.6-luna"))
        }

        @Test
        fun `should send low reasoning and a completion token budget for GPT-6 Luna scoring`() {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val requestBody = AtomicReference<String>()
            server.createContext("/v1/chat/completions") { exchange ->
                requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
                val response =
                    """
                    {
                      "id": "test",
                      "model": "gpt-6-luna",
                      "choices": [{"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}]
                    }
                    """.trimIndent().toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            try {
                val properties = AiProviderProperties(codexBaseUrl = "http://127.0.0.1:${server.address.port}")
                val factory = ChatClientFactory(properties, ObservationRegistry.NOOP)
                val provider = TestFixtures.userAiProviderEntity(provider = AiProvider.CODEX, apiKey = "", modelId = "gpt-6-luna")

                assertEquals("ok", factory.createForProvider(provider).prompt("ping").call().content())

                val request = ObjectMapper().readTree(requestBody.get())
                assertEquals("gpt-6-luna", request.path("model").asText())
                assertEquals("low", request.path("reasoning_effort").asText())
                assertEquals(500, request.path("max_completion_tokens").asInt())
                assertFalse(request.has("temperature"))
                assertFalse(request.has("max_tokens"))
            } finally {
                server.stop(0)
            }
        }

        @Test
        fun `should not treat gpt-4 models as reasoning models`() {
            assertFalse(ChatClientFactory.isReasoningModel("gpt-4o-mini"))
        }
    }

    @Nested
    inner class CreateChain {
        private val factory = ChatClientFactory(AiProviderProperties(codexBaseUrl = CODEX_BASE_URL), ObservationRegistry.NOOP)

        @Test
        fun `should exclude disabled rows from the chain`() {
            val enabled =
                TestFixtures.userAiProviderEntity(priority = 1, provider = AiProvider.OPENAI, apiKey = "sk-test", enabled = true)
            val disabled =
                TestFixtures.userAiProviderEntity(priority = 2, provider = AiProvider.CODEX, apiKey = "", enabled = false)

            val chain = factory.createChain(listOf(enabled, disabled))

            assertEquals(listOf(AiProvider.OPENAI), chain.links.map { it.provider })
        }

        @Test
        fun `should preserve priority order regardless of input order`() {
            val second = TestFixtures.userAiProviderEntity(priority = 2, provider = AiProvider.CODEX, apiKey = "")
            val first = TestFixtures.userAiProviderEntity(priority = 1, provider = AiProvider.OPENAI, apiKey = "sk-test")

            val chain = factory.createChain(listOf(second, first))

            assertEquals(listOf(AiProvider.OPENAI, AiProvider.CODEX), chain.links.map { it.provider })
        }

        @Test
        fun `should skip a row that fails to build and keep the rest of the chain`() {
            val broken = TestFixtures.userAiProviderEntity(priority = 1, provider = AiProvider.OPENAI, apiKey = "")
            val working = TestFixtures.userAiProviderEntity(priority = 2, provider = AiProvider.CODEX, apiKey = "")

            val chain = factory.createChain(listOf(broken, working))

            assertEquals(listOf(AiProvider.CODEX), chain.links.map { it.provider })
        }

        @Test
        fun `should name every provider when all rows fail to build`() {
            val brokenOpenAi = TestFixtures.userAiProviderEntity(priority = 1, provider = AiProvider.OPENAI, apiKey = "")
            val brokenGemini = TestFixtures.userAiProviderEntity(priority = 2, provider = AiProvider.GEMINI, apiKey = "")
            val plainFactory = ChatClientFactory(AiProviderProperties(), ObservationRegistry.NOOP)

            val chain = plainFactory.createChain(listOf(brokenOpenAi, brokenGemini))

            assertTrue(chain.links.isEmpty())
            assertEquals(2, chain.buildFailures.size)
            assertTrue(chain.buildFailures.any { it.contains("openai") })
            assertTrue(chain.buildFailures.any { it.contains("gemini") })
        }
    }

    @Nested
    inner class ObservationWiring {
        @Test
        fun `should route a chat model call through the injected observation registry`() {
            val observedContexts = mutableListOf<Observation.Context>()
            val recordingHandler =
                object : ObservationHandler<Observation.Context> {
                    override fun onStart(context: Observation.Context) {
                        observedContexts += context
                    }

                    override fun supportsContext(context: Observation.Context) = true
                }
            val registry = ObservationRegistry.create()
            registry.observationConfig().observationHandler(recordingHandler)
            val factory = ChatClientFactory(AiProviderProperties(codexBaseUrl = UNREACHABLE_BASE_URL), registry)
            val provider =
                TestFixtures.userAiProviderEntity(provider = AiProvider.CODEX, apiKey = "", modelId = "gpt-4o-mini")
            val chatClient = factory.createForProvider(provider)

            runCatching { chatClient.prompt("ping").call().content() }

            assertTrue(observedContexts.any { it is ChatModelObservationContext })
        }
    }
}
