package com.mshykhov.jobhunter.application.automation

import com.mshykhov.jobhunter.infrastructure.automation.AutomationProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class AutomationHealthPolicy(private val properties: AutomationProperties) {
    fun currentComponents(
        components: Map<AutomationComponent, AutomationComponentSnapshot>,
        now: Instant,
    ): Map<AutomationComponent, AutomationComponentSnapshot> =
        components.mapValues { (component, snapshot) ->
            if (isFresh(snapshot, component, now)) {
                snapshot
            } else {
                snapshot.copy(state = AutomationState.UNAVAILABLE, reason = AutomationReason.INVALID_REPORT)
            }
        }

    fun overallState(
        components: Map<AutomationComponent, AutomationComponentSnapshot>,
        now: Instant,
    ): AutomationState {
        if (components.values.any { it.state == AutomationState.AUTH_REQUIRED }) return AutomationState.AUTH_REQUIRED
        if (AutomationComponent.entries.any { component -> !isFresh(components[component], component, now) }) {
            return AutomationState.UNAVAILABLE
        }
        if (components.values.any { it.state != AutomationState.READY }) return AutomationState.DEGRADED
        return AutomationState.READY
    }

    fun overallReason(
        components: Map<AutomationComponent, AutomationComponentSnapshot>,
        state: AutomationState,
    ): AutomationReason =
        if (state == AutomationState.READY) {
            AutomationReason.NONE
        } else {
            components.values.firstOrNull { it.state == state }?.reason ?: AutomationReason.INVALID_REPORT
        }

    private fun isFresh(
        snapshot: AutomationComponentSnapshot?,
        component: AutomationComponent,
        now: Instant,
    ): Boolean {
        if (snapshot == null) return false
        val freshness = freshness(component)
        return !snapshot.checkedAt.isBefore(now.minus(freshness)) && !snapshot.checkedAt.isAfter(now.plus(properties.maxClockSkew))
    }

    private fun freshness(component: AutomationComponent): Duration =
        when (component) {
            AutomationComponent.CODEX -> properties.codexFreshness
            AutomationComponent.CHROME,
            AutomationComponent.PLAYWRIGHT,
            AutomationComponent.BROWSER_MCP,
            AutomationComponent.JOB_HUNTER_MCP,
            -> properties.preflightFreshness
            AutomationComponent.LAUNCHER,
            AutomationComponent.API,
            AutomationComponent.DATABASE,
            -> properties.heartbeatFreshness
        }
}
