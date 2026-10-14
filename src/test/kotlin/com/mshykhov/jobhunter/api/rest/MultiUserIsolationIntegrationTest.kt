package com.mshykhov.jobhunter.api.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunter.application.job.JobGroupRepository
import com.mshykhov.jobhunter.application.job.JobSource
import com.mshykhov.jobhunter.application.materials.CandidateProfileService
import com.mshykhov.jobhunter.application.user.UserRepository
import com.mshykhov.jobhunter.application.userjob.UserJobGroupRepository
import com.mshykhov.jobhunter.application.userjob.UserJobStatus
import com.mshykhov.jobhunter.support.AbstractIntegrationTest
import com.mshykhov.jobhunter.support.SyntheticMaterialBundle
import com.mshykhov.jobhunter.support.TestFixtures
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class MultiUserIsolationIntegrationTest : AbstractIntegrationTest() {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var candidateProfileService: CandidateProfileService

    @Autowired lateinit var userRepository: UserRepository

    @Autowired lateinit var jobGroupRepository: JobGroupRepository

    @Autowired lateinit var userJobGroupRepository: UserJobGroupRepository

    @Test
    fun `two subjects keep search preferences separate while scraper criteria aggregate allowed users`() {
        val owner = subject()
        val secondUser = subject()
        val ownerCategory = "backend engineer ${UUID.randomUUID()}"
        val secondCategory = "virtual assistant ${UUID.randomUUID()}"
        val secondDisabled = JobSource.entries.filterNot { it == JobSource.LINKEDIN }.map { it.value }

        saveSearch(owner, ownerCategory, listOf("Ukraine"), emptyList())
        saveSearch(secondUser, secondCategory, listOf("Indonesia"), secondDisabled)

        mockMvc.get("/preferences") { with(authentication(token(owner))) }.andExpect {
            status { isOk() }
            jsonPath("$.search.categories[0]", equalTo(ownerCategory))
            jsonPath("$.search.disabledSources", hasSize<Any>(0))
        }
        mockMvc.get("/preferences") { with(authentication(token(secondUser))) }.andExpect {
            status { isOk() }
            jsonPath("$.search.categories[0]", equalTo(secondCategory))
            jsonPath("$.search.disabledSources", hasSize<Any>(secondDisabled.size))
        }
        mockMvc.get("/criteria") {
            with(authentication(token(owner)))
            param("source", JobSource.LINKEDIN.value)
        }.andExpect {
            status { isOk() }
            jsonPath("$.categories", hasItem(ownerCategory))
            jsonPath("$.categories", hasItem(secondCategory))
        }
        mockMvc.get("/criteria") {
            with(authentication(token(owner)))
            param("source", JobSource.DOU.value)
        }.andExpect {
            status { isOk() }
            jsonPath("$.categories", hasItem(ownerCategory))
            jsonPath("$.categories", not(hasItem(secondCategory)))
        }
    }

    @Test
    fun `candidate profiles are listed only for their subject`() {
        val owner = subject()
        val secondUser = subject()
        val bundle = SyntheticMaterialBundle.create(objectMapper)
        val imported =
            candidateProfileService.importProfile(
                owner,
                bundle.manifest,
                bundle.candidateProfile,
                bundle.factCatalog,
                bundle.writingStyle,
                bundle.baseDocx,
                bundle.basePdf,
            )

        mockMvc.get("/materials/profiles") { with(authentication(token(owner))) }.andExpect {
            status { isOk() }
            jsonPath("$", hasSize<Any>(1))
            jsonPath("$[0].id", equalTo(imported.id.toString()))
        }
        mockMvc.get("/materials/profiles") { with(authentication(token(secondUser))) }.andExpect {
            status { isOk() }
            jsonPath("$", hasSize<Any>(0))
        }
    }

    @Test
    fun `one subject cannot search or change another subjects job status`() {
        val owner = subject()
        val secondUser = subject()
        val ownerUser = userRepository.save(TestFixtures.userEntity(owner))
        val second = userRepository.save(TestFixtures.userEntity(secondUser))
        val ownerGroup = jobGroupRepository.save(TestFixtures.jobGroupEntity("Owner role ${UUID.randomUUID()}"))
        val secondGroup = jobGroupRepository.save(TestFixtures.jobGroupEntity("Second role ${UUID.randomUUID()}"))
        val ownerMatch = userJobGroupRepository.save(TestFixtures.userJobGroupEntity(user = ownerUser, group = ownerGroup))
        val secondMatch = userJobGroupRepository.save(TestFixtures.userJobGroupEntity(user = second, group = secondGroup))

        mockMvc.post("/jobs/search") {
            with(authentication(token(secondUser)))
            contentType = APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
            jsonPath("$.content", hasSize<Any>(1))
            jsonPath("$.content[0].groupId", equalTo(secondGroup.id.toString()))
        }
        mockMvc.get("/jobs/groups/${ownerGroup.id}") { with(authentication(token(secondUser))) }
            .andExpect { status { isNotFound() } }
        mockMvc.patch("/jobs/groups/${ownerGroup.id}/status") {
            with(authentication(token(secondUser)))
            contentType = APPLICATION_JSON
            content = """{"status":"APPLIED"}"""
        }.andExpect { status { isNotFound() } }

        assertEquals(UserJobStatus.NEW, userJobGroupRepository.findById(ownerMatch.id).orElseThrow().status)
        assertEquals(UserJobStatus.NEW, userJobGroupRepository.findById(secondMatch.id).orElseThrow().status)
    }

    private fun saveSearch(subject: String, category: String, locations: List<String>, disabledSources: List<String>) {
        mockMvc.put("/preferences/search") {
            with(authentication(token(subject)))
            contentType = APPLICATION_JSON
            content =
                objectMapper.writeValueAsString(
                    mapOf(
                        "categories" to listOf(category),
                        "locations" to locations,
                        "remoteOnly" to true,
                        "disabledSources" to disabledSources,
                    ),
                )
        }.andExpect { status { isOk() } }
    }

    private fun subject(): String = "user-${UUID.randomUUID()}@example.test"

    private fun token(subject: String): JwtAuthenticationToken {
        val permissions = listOf("read:jobs", "write:jobs", "read:criteria", "read:preferences", "write:preferences")
        val jwt =
            Jwt
                .withTokenValue("test-token")
                .header("alg", "none")
                .issuer("http://localhost/dev")
                .subject(subject)
                .claim("permissions", permissions)
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build()
        return JwtAuthenticationToken(jwt, permissions.map { SimpleGrantedAuthority("SCOPE_$it") })
    }
}
