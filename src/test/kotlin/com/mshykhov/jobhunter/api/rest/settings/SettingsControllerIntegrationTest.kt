package com.mshykhov.jobhunter.api.rest.settings

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunter.application.ai.UserAiProviderRepository
import com.mshykhov.jobhunter.support.AbstractIntegrationTest
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals

class SettingsControllerIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var userAiProviderRepository: UserAiProviderRepository

    @Nested
    inner class AiProviders {
        @Test
        fun `should return list of AI providers with models`() {
            mockMvc.get("/settings/ai-providers").andExpect {
                status { isOk() }
                jsonPath("$.providers", hasSize<Any>(greaterThan(0)))
                jsonPath("$.providers[0].id") { isNotEmpty() }
                jsonPath("$.providers[0].models", hasSize<Any>(greaterThan(0)))
            }
        }

        @Test
        fun `should expose requiresApiKey per provider`() {
            mockMvc.get("/settings/ai-providers").andExpect {
                status { isOk() }
                jsonPath("$.providers[?(@.id=='codex')].requiresApiKey", equalTo(listOf(false)))
                jsonPath("$.providers[?(@.id=='groq')].requiresApiKey", equalTo(listOf(true)))
                jsonPath("$.providers[?(@.id=='nvidia')].requiresApiKey", equalTo(listOf(true)))
                jsonPath("$.providers[?(@.id=='openai')].requiresApiKey", equalTo(listOf(true)))
            }
        }
    }

    @Nested
    inner class AiSettings {
        @Test
        fun `should save AI settings and return masked key`() {
            val body =
                mapOf(
                    "apiKey" to "sk-ant-api03-very-secret-key-12345",
                    "modelId" to "claude-sonnet-4-20250514",
                )

            mockMvc
                .put("/settings/ai") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(body)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.modelId", equalTo("claude-sonnet-4-20250514"))
                    jsonPath("$.apiKeyHint", startsWith("sk-ant-a"))
                }
        }

        @Test
        fun `should update existing AI settings`() {
            mockMvc
                .put("/settings/ai") {
                    contentType = APPLICATION_JSON
                    content = """{"apiKey": "sk-first-key-123456", "modelId": "old-model"}"""
                }.andExpect { status { isOk() } }

            mockMvc
                .put("/settings/ai") {
                    contentType = APPLICATION_JSON
                    content = """{"apiKey": "sk-second-key-789012", "modelId": "new-model"}"""
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.modelId", equalTo("new-model"))
                }

            mockMvc.get("/settings/ai").andExpect {
                status { isOk() }
                jsonPath("$.modelId", equalTo("new-model"))
            }
        }

        @Test
        fun `should keep stored key when apiKey is blank on update`() {
            mockMvc
                .put("/settings/ai") {
                    contentType = APPLICATION_JSON
                    content = """{"apiKey": "sk-keep-me-123456", "modelId": "old-model"}"""
                }.andExpect { status { isOk() } }

            mockMvc
                .put("/settings/ai") {
                    contentType = APPLICATION_JSON
                    content = """{"apiKey": "", "modelId": "updated-model"}"""
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.modelId", equalTo("updated-model"))
                    jsonPath("$.apiKeyHint", startsWith("sk-keep-"))
                }
        }

        @Test
        fun `should return 400 when apiKey is blank and no settings exist`() {
            userAiProviderRepository.deleteAll()

            mockMvc
                .put("/settings/ai") {
                    contentType = APPLICATION_JSON
                    content = """{"apiKey": "", "modelId": "some-model"}"""
                }.andExpect {
                    status { isBadRequest() }
                }
        }

        @Test
        fun `should return 400 when modelId is blank`() {
            mockMvc
                .put("/settings/ai") {
                    contentType = APPLICATION_JSON
                    content = """{"apiKey": "sk-valid-key", "modelId": ""}"""
                }.andExpect {
                    status { isBadRequest() }
                }
        }
    }

    @Nested
    inner class AiProviderChain {
        @Test
        fun `should replace chain with validated ordered list and read it back`() {
            val body =
                mapOf(
                    "chain" to
                        listOf(
                            mapOf("priority" to 1, "provider" to "CODEX", "modelId" to "gpt-5.6-luna", "enabled" to true),
                            mapOf(
                                "priority" to 2,
                                "provider" to "OPENAI",
                                "modelId" to "gpt-4o-mini",
                                "apiKey" to "sk-chain-test-key-123456",
                                "enabled" to true,
                            ),
                        ),
                )

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(body)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.chain", hasSize<Any>(2))
                    jsonPath("$.chain[0].provider", equalTo("codex"))
                    jsonPath("$.chain[1].provider", equalTo("openai"))
                    jsonPath("$.chain[1].apiKeyHint", startsWith("sk-chain"))
                }

            mockMvc.get("/settings/ai/providers").andExpect {
                status { isOk() }
                jsonPath("$.chain", hasSize<Any>(2))
                jsonPath("$.chain[0].provider", equalTo("codex"))
            }
        }

        @Test
        fun `should accept the exact provider spelling that the catalogue and the chain GET both return`() {
            val providersResponse = mockMvc.get("/settings/ai-providers").andReturn().response.contentAsString
            val catalogueId = objectMapper.readTree(providersResponse).get("providers").first { it.get("id").asText() == "codex" }.get("id").asText()

            val body = mapOf("chain" to listOf(mapOf("priority" to 1, "provider" to catalogueId, "modelId" to "gpt-5.6-luna")))

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(body)
                }.andExpect { status { isOk() } }

            val chainGetResponse = mockMvc.get("/settings/ai/providers").andReturn().response.contentAsString
            val returnedProvider = objectMapper.readTree(chainGetResponse).get("chain")[0].get("provider").asText()
            assertEquals(catalogueId, returnedProvider)

            val roundTripBody = mapOf("chain" to listOf(mapOf("priority" to 1, "provider" to returnedProvider, "modelId" to "gpt-5.6-luna")))

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(roundTripBody)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.chain[0].provider", equalTo(returnedProvider))
                }
        }

        @Test
        fun `should keep the stored key when a reorder omits it`() {
            val initialBody =
                mapOf(
                    "chain" to
                        listOf(
                            mapOf(
                                "priority" to 1,
                                "provider" to "openai",
                                "modelId" to "gpt-4o-mini",
                                "apiKey" to "sk-keep-on-reorder-123456",
                            ),
                        ),
                )

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(initialBody)
                }.andExpect { status { isOk() } }

            val reorderedBody =
                mapOf(
                    "chain" to
                        listOf(
                            mapOf("priority" to 1, "provider" to "codex", "modelId" to "gpt-5.6-luna"),
                            mapOf("priority" to 2, "provider" to "openai", "modelId" to "gpt-4o-mini"),
                        ),
                )

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(reorderedBody)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.chain[1].provider", equalTo("openai"))
                    jsonPath("$.chain[1].apiKeyHint", startsWith("sk-keep-"))
                }
        }

        @Test
        fun `should accept a CODEX entry without an api key`() {
            val body = mapOf("chain" to listOf(mapOf("priority" to 1, "provider" to "CODEX", "modelId" to "gpt-5.6-luna")))

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(body)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.chain[0].apiKeyHint", equalTo("No API key required"))
                }
        }

        @Test
        fun `should accept a re-ordered chain replacing a previously saved one`() {
            val initialBody =
                mapOf(
                    "chain" to
                        listOf(
                            mapOf(
                                "priority" to 1,
                                "provider" to "OPENAI",
                                "modelId" to "gpt-4o-mini",
                                "apiKey" to "sk-initial-key-123456",
                            ),
                        ),
                )

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(initialBody)
                }.andExpect { status { isOk() } }

            val reorderedBody =
                mapOf(
                    "chain" to
                        listOf(
                            mapOf("priority" to 1, "provider" to "CODEX", "modelId" to "gpt-5.6-luna"),
                            mapOf(
                                "priority" to 2,
                                "provider" to "OPENAI",
                                "modelId" to "gpt-4o-mini",
                                "apiKey" to "sk-initial-key-123456",
                            ),
                        ),
                )

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(reorderedBody)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.chain", hasSize<Any>(2))
                    jsonPath("$.chain[0].provider", equalTo("codex"))
                    jsonPath("$.chain[1].provider", equalTo("openai"))
                }
        }

        @Test
        fun `should reject non-contiguous priorities`() {
            val body =
                mapOf(
                    "chain" to
                        listOf(
                            mapOf("priority" to 1, "provider" to "CODEX", "modelId" to "gpt-5.6-luna"),
                            mapOf("priority" to 3, "provider" to "OPENAI", "modelId" to "gpt-4o-mini", "apiKey" to "sk-key-123456"),
                        ),
                )

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(body)
                }.andExpect { status { isBadRequest() } }
        }

        @Test
        fun `should reject a provider listed twice`() {
            val body =
                mapOf(
                    "chain" to
                        listOf(
                            mapOf("priority" to 1, "provider" to "OPENAI", "modelId" to "gpt-4o-mini", "apiKey" to "sk-key-one-123456"),
                            mapOf("priority" to 2, "provider" to "OPENAI", "modelId" to "gpt-4o", "apiKey" to "sk-key-two-123456"),
                        ),
                )

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(body)
                }.andExpect { status { isBadRequest() } }
        }

        @Test
        fun `should reject an OPENAI entry with a blank api key`() {
            val body =
                mapOf("chain" to listOf(mapOf("priority" to 1, "provider" to "OPENAI", "modelId" to "gpt-4o-mini", "apiKey" to "")))

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(body)
                }.andExpect { status { isBadRequest() } }
        }

        @Test
        fun `should reject an empty chain`() {
            val body = mapOf("chain" to emptyList<Any>())

            mockMvc
                .put("/settings/ai/providers") {
                    contentType = APPLICATION_JSON
                    content = objectMapper.writeValueAsString(body)
                }.andExpect { status { isBadRequest() } }
        }
    }
}
