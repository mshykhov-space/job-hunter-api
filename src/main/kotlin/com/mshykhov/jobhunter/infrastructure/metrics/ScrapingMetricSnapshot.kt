package com.mshykhov.jobhunter.infrastructure.metrics

data class ScrapingMetricSnapshot(
    val enabled: Long,
    val lastSuccessTimestampSeconds: Long,
    val lastErrorTimestampSeconds: Long,
    val runsStarted: Long,
    val runsSucceeded: Long,
    val runsFailed: Long,
    val jobsFetched: Long,
    val jobsAccepted: Long,
)
