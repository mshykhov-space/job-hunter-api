package com.mshykhov.jobhunter.api.rest.criteria

import com.mshykhov.jobhunter.application.job.JobSource
import com.mshykhov.jobhunter.support.AbstractIntegrationTest
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import org.springframework.transaction.annotation.Transactional

class SearchCriteriaControllerIntegrationTest : AbstractIntegrationTest() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Nested
    inner class GetCriteria {
        @Test
        fun `should return criteria for valid source`() {
            mockMvc
                .get("/criteria") {
                    param("source", JobSource.DOU.value)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.categories") { isArray() }
                    jsonPath("$.locations") { isArray() }
                    jsonPath("$.remoteOnly") { isBoolean() }
                }
        }

        @Test
        @Transactional
        fun `should return criteria reflecting saved preferences`() {
            jdbcTemplate.update("DELETE FROM user_preferences")
            mockMvc
                .put("/preferences/search") {
                    contentType = APPLICATION_JSON
                    content =
                        """{"categories": ["Backend", "DevOps"], "locations": ["Kyiv"], "remoteOnly": true, "disabledSources": []}"""
                }.andExpect { status { isOk() } }

            mockMvc
                .get("/criteria") {
                    param("source", JobSource.DOU.value)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.categories", hasSize<Any>(2))
                    jsonPath("$.locations", hasSize<Any>(1))
                    jsonPath("$.remoteOnly", equalTo(true))
                }
        }

        @Test
        fun `should return 400 when source parameter is missing`() {
            mockMvc.get("/criteria").andExpect {
                status { isBadRequest() }
            }
        }

        @Test
        fun `should return 400 for invalid source value`() {
            mockMvc
                .get("/criteria") {
                    param("source", "INVALID_SOURCE")
                }.andExpect {
                    status { isBadRequest() }
                }
        }

        @Test
        fun `should work for each job source`() {
            JobSource.entries.forEach { source ->
                mockMvc
                    .get("/criteria") {
                        param("source", source.value)
                    }.andExpect {
                        status { isOk() }
                    }
            }
        }
    }
}
