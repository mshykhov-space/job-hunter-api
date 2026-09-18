package com.mshykhov.jobhunter.application.automation

import com.mshykhov.jobhunter.api.rest.automation.dto.AutomationDelegationResponse
import com.mshykhov.jobhunter.api.rest.automation.dto.AutomationHeartbeatRequest
import com.mshykhov.jobhunter.api.rest.automation.dto.AutomationHeartbeatResponse
import com.mshykhov.jobhunter.api.rest.automation.dto.AutomationSessionResponse
import com.mshykhov.jobhunter.api.rest.automation.dto.AutomationStatusResponse
import com.mshykhov.jobhunter.application.automation.workflow.AutomationWorkflowService
import com.mshykhov.jobhunter.application.common.ConflictException
import com.mshykhov.jobhunter.application.common.NotFoundException
import com.mshykhov.jobhunter.application.common.StaleAutomationGenerationException
import com.mshykhov.jobhunter.application.common.ValidationException
import com.mshykhov.jobhunter.application.user.UserFacade
import com.mshykhov.jobhunter.infrastructure.automation.AutomationProperties
import com.mshykhov.jobhunter.infrastructure.metrics.AutomationMetrics
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Service
class AutomationService(
    private val facade: AutomationFacade,
    private val userFacade: UserFacade,
    private val healthPolicy: AutomationHealthPolicy,
    private val properties: AutomationProperties,
    private val clock: Clock,
    private val metrics: AutomationMetrics,
    private val workflowService: AutomationWorkflowService,
) {
    @Transactional
    fun enableDelegation(): AutomationDelegationResponse {
        val existing = facade.findDelegation(properties.ownerIssuer, properties.ownerSubject)
        if (existing != null) {
            existing.healthReportingEnabled = true
            existing.revokedAt = null
            facade.saveDelegation(existing)
            afterCommit { metrics.setEnabled(true) }
            return AutomationDelegationResponse(enabled = true)
        }
        val user = userFacade.findOrCreate(properties.ownerSubject)
        facade.saveDelegation(
            AutomationDelegationEntity(
                user = user,
                ownerIssuer = properties.ownerIssuer,
                ownerSubject = properties.ownerSubject,
                runnerIssuer = properties.runnerIssuer,
            ),
        )
        afterCommit { metrics.setEnabled(true) }
        return AutomationDelegationResponse(enabled = true)
    }

    @Transactional
    fun revokeDelegation() {
        facade.findActiveDelegation(properties.ownerIssuer, properties.ownerSubject)?.let {
            it.healthReportingEnabled = false
            it.revokedAt = Instant.now(clock)
            facade.saveDelegation(it)
            afterCommit { metrics.setEnabled(false) }
        }
    }

    fun status(): AutomationStatusResponse {
        val delegation = facade.findActiveDelegation(properties.ownerIssuer, properties.ownerSubject)
            ?: return unavailableStatus(enabled = false)
        if (!delegation.healthReportingEnabled) return unavailableStatus(enabled = false)
        val runner = facade.findRunner(delegation.id) ?: return unavailableStatus(enabled = true)
        val now = Instant.now(clock)
        val components = healthPolicy.currentComponents(runner.components, now)
        val state = healthPolicy.overallState(components, now)
        return AutomationStatusResponse(
            enabled = true,
            state = state,
            reason = healthPolicy.overallReason(components, state),
            components = components,
            launcherVersion = runner.launcherVersion,
            lastHeartbeatAt = runner.lastHeartbeatAt,
            lastPreflightSuccessAt = runner.lastPreflightSuccessAt,
            lastCodexSuccessAt = runner.lastCodexSuccessAt,
        )
    }

    @Transactional
    fun startSession(): AutomationSessionResponse {
        val delegation = activeDelegation()
        val runner = facade.findRunnerForUpdate(delegation.id)
            ?: AutomationRunnerEntity(delegation = delegation, runnerKey = RUNNER_KEY)
        runner.generation += 1
        runner.sequence = 0
        runner.lastIdempotencyKey = null
        facade.saveRunner(runner)
        workflowService.onRunnerGenerationStarted(delegation.id, runner.generation)
        return AutomationSessionResponse(RUNNER_KEY, runner.generation, 60, 300, 21600)
    }

    @Transactional
    fun recordHeartbeat(request: AutomationHeartbeatRequest): AutomationHeartbeatResponse {
        val delegation = activeDelegation()
        val runner = facade.findRunnerForUpdate(delegation.id) ?: throw NotFoundException("Runner session not found")
        if (request.generation != runner.generation) throw StaleAutomationGenerationException()
        if (request.idempotencyKey == runner.lastIdempotencyKey) return heartbeatResponse(runner)
        if (request.sequence != runner.sequence + 1) throw ConflictException("Unexpected heartbeat sequence")
        val now = Instant.now(clock)
        if (Duration.between(request.sentAt, now).abs() > properties.maxClockSkew) {
            throw ValidationException("Heartbeat clock skew exceeds the allowed limit")
        }
        val previous = runner.components
        val previousProbes = runner.probes
        val state = healthPolicy.overallState(request.components, now)
        runner.sequence = request.sequence
        runner.lastIdempotencyKey = request.idempotencyKey
        runner.launcherVersion = request.launcherVersion
        runner.components = request.components
        runner.probes = request.probes
        runner.overallState = state
        runner.overallReason = healthPolicy.overallReason(request.components, state)
        runner.lastHeartbeatAt = now
        runner.lastPreflightSuccessAt = request.lastPreflightSuccessAt
        runner.lastCodexSuccessAt = request.lastCodexSuccessAt
        runner.codexInputTokens = request.codexInputTokens
        runner.codexOutputTokens = request.codexOutputTokens
        facade.saveRunner(runner)
        facade.appendTransitions(transitions(runner, previous, request.components, now))
        afterCommit {
            metrics.recordHeartbeat(
                lastHeartbeatAt = now,
                components = request.components,
                probes = request.probes,
                executedProbes = request.probes.filter { (probe, snapshot) -> previousProbes[probe] != snapshot },
                codexInputTokens = request.codexInputTokens,
                codexOutputTokens = request.codexOutputTokens,
            )
        }
        return heartbeatResponse(runner)
    }

    private fun activeDelegation(): AutomationDelegationEntity =
        facade.findActiveDelegation(properties.ownerIssuer, properties.ownerSubject)
            ?.takeIf { it.healthReportingEnabled }
            ?: throw NotFoundException("Active automation delegation not found")

    private fun transitions(
        runner: AutomationRunnerEntity,
        previous: Map<AutomationComponent, AutomationComponentSnapshot>,
        current: Map<AutomationComponent, AutomationComponentSnapshot>,
        now: Instant,
    ): List<AutomationRunnerTransitionEntity> =
        current.mapNotNull { (component, snapshot) ->
            val oldState = previous[component]?.state ?: AutomationState.UNAVAILABLE
            if (oldState == snapshot.state) null else AutomationRunnerTransitionEntity(
                runner = runner,
                component = component,
                fromState = oldState,
                toState = snapshot.state,
                reason = snapshot.reason,
                occurredAt = now,
                generation = runner.generation,
                sequence = runner.sequence,
            )
        }

    private fun heartbeatResponse(runner: AutomationRunnerEntity) =
        AutomationHeartbeatResponse(runner.generation, runner.sequence, runner.overallState)

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private fun unavailableStatus(enabled: Boolean) =
        AutomationStatusResponse(
            enabled = enabled,
            state = AutomationState.UNAVAILABLE,
            reason = AutomationReason.INVALID_REPORT,
            components = emptyMap(),
            launcherVersion = null,
            lastHeartbeatAt = null,
            lastPreflightSuccessAt = null,
            lastCodexSuccessAt = null,
        )

    private companion object {
        const val RUNNER_KEY = "primary"
    }
}
