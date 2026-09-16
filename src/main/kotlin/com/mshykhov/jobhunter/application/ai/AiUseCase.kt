package com.mshykhov.jobhunter.application.ai

enum class AiUseCase(val temperature: Double, val reasoningEffort: String, val maxCompletionTokens: Int) {
    SCORING(0.2, "low", 500),
    OUTREACH(0.7, "medium", 2_000),
    EXTRACTION(0.1, "low", 1_500),
    OPTIMIZATION(0.3, "low", 2_000),
}
