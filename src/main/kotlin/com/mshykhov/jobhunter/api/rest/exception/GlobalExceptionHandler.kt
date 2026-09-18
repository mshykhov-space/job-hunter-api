package com.mshykhov.jobhunter.api.rest.exception

import com.mshykhov.jobhunter.application.common.AiNotConfiguredException
import com.mshykhov.jobhunter.application.common.AutomationLeaseLostException
import com.mshykhov.jobhunter.application.common.ConflictException
import com.mshykhov.jobhunter.application.common.NotFoundException
import com.mshykhov.jobhunter.application.common.ScrapingLeaseLostException
import com.mshykhov.jobhunter.application.common.ScrapingSourceDisabledException
import com.mshykhov.jobhunter.application.common.ServiceUnavailableException
import com.mshykhov.jobhunter.application.common.StaleAutomationGenerationException
import com.mshykhov.jobhunter.application.common.ValidationException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.security.authorization.AuthorizationDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

private val log = KotlinLogging.logger {}

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {
    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.warn { "Validation error: $errors" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(errors, "VALIDATION_ERROR"))
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val status = HttpStatus.resolve(statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        val code = status.name
        log.warn { "${status.value()} $code: ${ex.message}" }
        return ResponseEntity
            .status(status)
            .headers(headers)
            .body(ErrorResponse(status.reasonPhrase, code))
    }

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFound(ex: NotFoundException): ResponseEntity<ErrorResponse> {
        log.warn { "Not found: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(ex.message ?: "Not found", "NOT_FOUND"))
    }

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(ex: ValidationException): ResponseEntity<ErrorResponse> {
        log.warn { "Validation error: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(ex.message ?: "Validation error", "VALIDATION_ERROR"))
    }

    @ExceptionHandler(AiNotConfiguredException::class)
    fun handleAiNotConfigured(ex: AiNotConfiguredException): ResponseEntity<ErrorResponse> {
        log.warn { "AI not configured: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse(ex.message ?: "AI not configured", "AI_NOT_CONFIGURED"))
    }

    @ExceptionHandler(ServiceUnavailableException::class)
    fun handleServiceUnavailable(ex: ServiceUnavailableException): ResponseEntity<ErrorResponse> {
        log.error { "Service unavailable: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse(ex.message ?: "Service unavailable", "SERVICE_UNAVAILABLE"))
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleConflict(ex: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        log.warn { "Data conflict: ${ex.mostSpecificCause.message}" }
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse("Data conflict: duplicate or constraint violation", "CONFLICT"))
    }

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ResponseEntity<ErrorResponse> {
        log.warn { "Conflict: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message ?: "Conflict", "CONFLICT"))
    }

    @ExceptionHandler(StaleAutomationGenerationException::class)
    fun handleStaleAutomationGeneration(ex: StaleAutomationGenerationException): ResponseEntity<ErrorResponse> {
        log.warn { ex.message }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty(), "STALE_AUTOMATION_GENERATION"))
    }

    @ExceptionHandler(AutomationLeaseLostException::class)
    fun handleAutomationLeaseLost(ex: AutomationLeaseLostException): ResponseEntity<ErrorResponse> {
        log.warn { ex.message }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty(), "AUTOMATION_LEASE_LOST"))
    }

    @ExceptionHandler(ScrapingLeaseLostException::class)
    fun handleScrapingLeaseLost(ex: ScrapingLeaseLostException): ResponseEntity<ErrorResponse> {
        log.warn { ex.message }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty(), "SCRAPING_LEASE_LOST"))
    }

    @ExceptionHandler(ScrapingSourceDisabledException::class)
    fun handleScrapingSourceDisabled(ex: ScrapingSourceDisabledException): ResponseEntity<ErrorResponse> {
        log.warn { ex.message }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse(ex.message.orEmpty(), "SCRAPING_SOURCE_DISABLED"))
    }

    @ExceptionHandler(AuthorizationDeniedException::class)
    fun handleForbidden(ex: AuthorizationDeniedException): ResponseEntity<ErrorResponse> {
        log.warn { "Access denied: ${ex.message}" }
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse("Access denied", "FORBIDDEN"))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error(ex) { "Unexpected error" }
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("Internal server error", "INTERNAL_ERROR"))
    }
}
