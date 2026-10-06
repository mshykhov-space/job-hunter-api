package com.mshykhov.jobhunter.application.job

import com.mshykhov.jobhunter.support.TestFixtures
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@Testcontainers
class RemoteRecoveryMigrationTest {
    @Test
    fun `requeues lost remote matches without guessing flags or changing review state`() {
        val flyway = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        flyway.target("32").load().migrate()
        val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
        val user = TestFixtures.userEntity()
        val otherUser = TestFixtures.userEntity()
        for (fixture in listOf(user, otherUser)) {
            jdbc.update("INSERT INTO users (id, auth0_sub) VALUES (?, ?)", fixture.id, fixture.auth0Sub)
        }

        fun group(): UUID {
            val fixture = TestFixtures.jobGroupEntity(company = UUID.randomUUID().toString())
            jdbc.update("INSERT INTO job_groups (id, group_key, title) VALUES (?, ?, ?)", fixture.id, fixture.groupKey, fixture.title)
            return fixture.id
        }

        fun job(groupId: UUID, remote: Boolean?, description: String = "Fully remote Java backend"): UUID {
            val fixture = TestFixtures.jobEntity(remote = remote, description = description)
            jdbc.update(
                "INSERT INTO jobs (id, group_id, title, url, description, source, remote, matched_at) VALUES (?, ?, ?, ?, ?, 'LINKEDIN', ?, now())",
                fixture.id,
                groupId,
                fixture.title,
                fixture.url,
                description,
                remote,
            )
            return fixture.id
        }

        fun decision(groupId: UUID, remote: Boolean?, outcome: String = "AI_SCORED", userId: UUID = user.id) {
            jdbc.update(
                "INSERT INTO user_job_group_decisions (user_id, group_id, vacancy_seen_at, decided_at, outcome, inferred_remote) VALUES (?, ?, now(), now(), ?, ?)",
                userId,
                groupId,
                outcome,
                remote,
            )
        }

        val confirmedGroup = group()
        val confirmed = job(confirmedGroup, null)
        decision(confirmedGroup, true)
        jdbc.update("UPDATE jobs SET match_attempts = 5 WHERE id = ?", confirmed)
        jdbc.update(
            "INSERT INTO user_job_groups (user_id, group_id, status, ai_relevance_score, ai_reasoning) VALUES (?, ?, 'APPLIED', 96, 'Fully remote')",
            user.id,
            confirmedGroup,
        )
        val explicitGroup = group()
        val explicit = job(explicitGroup, false)
        decision(explicitGroup, true)
        val rejectedGroup = group()
        val rejected = job(rejectedGroup, null)
        decision(rejectedGroup, false, "AI_REJECTED_REMOTE")
        val knownGroup = group()
        val known = job(knownGroup, true)
        val knownDuplicate = job(knownGroup, null)
        decision(knownGroup, true)
        val conflictingGroup = group()
        val conflicting = job(conflictingGroup, null)
        decision(conflictingGroup, true)
        decision(conflictingGroup, false, userId = otherUser.id)
        val legacyGroup = group()
        val legacy = job(legacyGroup, null)
        decision(legacyGroup, null)
        val unscoredGroup = group()
        val unscored = job(unscoredGroup, null)
        decision(unscoredGroup, true, "COLD_ONLY")
        val shorterGroup = group()
        val shorter = job(shorterGroup, null, "Short")
        val longest = job(shorterGroup, false)
        decision(shorterGroup, true)
        val ambiguousGroup = group()
        val ambiguousOne = job(ambiguousGroup, null, "Remote A")
        val ambiguousTwo = job(ambiguousGroup, null, "Remote B")
        val ambiguousExplicit = job(ambiguousGroup, false, "Remote C")
        decision(ambiguousGroup, true)
        val duplicateGroup = group()
        val duplicateOne = job(duplicateGroup, null)
        val duplicateTwo = job(duplicateGroup, null)
        decision(duplicateGroup, true)

        val matchedGroups = listOf(
            explicitGroup, knownGroup, rejectedGroup, conflictingGroup, legacyGroup,
            unscoredGroup, shorterGroup, ambiguousGroup, duplicateGroup,
        )
        for (groupId in matchedGroups) {
            jdbc.update("INSERT INTO user_job_groups (user_id, group_id) VALUES (?, ?)", user.id, groupId)
        }
        val unmatchedGroup = group()
        val unmatched = job(unmatchedGroup, null)
        decision(unmatchedGroup, true)

        flyway.target("33").load().migrate()

        fun remote(jobId: UUID): Boolean? = jdbc.queryForObject("SELECT remote FROM jobs WHERE id = ?", Boolean::class.javaObjectType, jobId)

        assertEquals(null, remote(confirmed))
        assertEquals(null, jdbc.queryForObject("SELECT matched_at FROM jobs WHERE id = ?", java.sql.Timestamp::class.java, confirmed))
        assertEquals(0, jdbc.queryForObject("SELECT match_attempts FROM jobs WHERE id = ?", Int::class.javaObjectType, confirmed))
        assertEquals(false, remote(explicit))
        assertEquals(true, remote(known))
        assertEquals(null, remote(knownDuplicate))
        assertEquals(null, remote(unmatched))
        assertEquals(null, remote(rejected))
        assertEquals(null, remote(conflicting))
        assertEquals(null, remote(legacy))
        assertEquals(null, remote(unscored))
        assertEquals(null, remote(shorter))
        assertEquals(false, remote(longest))
        assertEquals(null, remote(ambiguousOne))
        assertEquals(null, remote(ambiguousTwo))
        assertEquals(false, remote(ambiguousExplicit))
        assertEquals(null, remote(duplicateOne))
        assertEquals(null, remote(duplicateTwo))
        assertEquals(7, jdbc.queryForObject("SELECT count(*) FROM jobs WHERE matched_at IS NULL", Int::class.javaObjectType))
        val queuedGroups = jdbc.query("SELECT group_id FROM jobs WHERE matched_at IS NULL") { rs, _ -> rs.getObject("group_id", UUID::class.java) }.toSet()
        assertEquals(setOf(confirmedGroup, conflictingGroup, ambiguousGroup, duplicateGroup), queuedGroups)
        assertEquals("APPLIED", jdbc.queryForObject("SELECT status FROM user_job_groups WHERE group_id = ?", String::class.java, confirmedGroup))
        assertEquals(96, jdbc.queryForObject("SELECT ai_relevance_score FROM user_job_groups WHERE group_id = ?", Int::class.javaObjectType, confirmedGroup))
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}
