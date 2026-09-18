package com.mshykhov.jobhunter.application.automation

import com.mshykhov.jobhunter.application.automation.workflow.AutomationWorkflowService
import com.mshykhov.jobhunter.application.user.UserFacade
import com.mshykhov.jobhunter.infrastructure.automation.AutomationProperties
import com.mshykhov.jobhunter.infrastructure.metrics.AutomationMetrics
import com.mshykhov.jobhunter.support.TestFixtures
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals

class AutomationServiceTest {
    private val now = Instant.parse("2026-09-18T12:00:00Z")
    private val clock = MutableClock(now)
    private val properties = AutomationProperties(ownerIssuer = "owner", ownerSubject = "subject")
    private val facade = mockk<AutomationFacade>()
    private val service =
        AutomationService(
            facade = facade,
            userFacade = mockk<UserFacade>(),
            healthPolicy = AutomationHealthPolicy(properties),
            properties = properties,
            clock = clock,
            metrics = mockk<AutomationMetrics>(),
            workflowService = mockk<AutomationWorkflowService>(),
        )

    @Test
    fun `status derives current state and component states from freshness`() {
        val delegation = TestFixtures.automationDelegationEntity(ownerIssuer = "owner", ownerSubject = "subject")
        val components =
            AutomationComponent.entries.associateWith {
                AutomationComponentSnapshot(
                    state = AutomationState.READY,
                    reason = AutomationReason.NONE,
                    checkedAt = now,
                    probeVersion = "0.1.0",
                )
            }
        val runner = TestFixtures.automationRunnerEntity(delegation = delegation, components = components)
        runner.overallState = AutomationState.READY
        runner.overallReason = AutomationReason.NONE

        every { facade.findActiveDelegation("owner", "subject") } returns delegation
        every { facade.findRunner(delegation.id) } returns runner

        assertEquals(AutomationState.READY, service.status().state)

        clock.advance(Duration.ofDays(1))
        val status = service.status()

        assertEquals(AutomationState.UNAVAILABLE, status.state)
        assertEquals(AutomationReason.INVALID_REPORT, status.reason)
        assertEquals(
            AutomationComponent.entries.toSet(),
            status.components.filterValues { it.state == AutomationState.UNAVAILABLE }.keys,
        )
    }

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
