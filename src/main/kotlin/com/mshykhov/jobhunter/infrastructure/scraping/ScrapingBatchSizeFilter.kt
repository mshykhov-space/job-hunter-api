package com.mshykhov.jobhunter.infrastructure.scraping

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunter.api.rest.exception.ErrorResponse
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

class ScrapingBatchSizeFilter(private val properties: ScrapingProperties, private val objectMapper: ObjectMapper) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != HttpMethod.POST.name() || !BATCH_PATH.matches(request.requestURI)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val limit = properties.maxBatchRequestSize.toBytes()
        require(limit in 1 until Int.MAX_VALUE) { "Scraping batch request limit must fit in an integer byte buffer" }
        val body = request.inputStream.readNBytes(limit.toInt() + 1)
        if (body.size > limit) {
            response.status = HttpStatus.PAYLOAD_TOO_LARGE.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            objectMapper.writeValue(response.writer, ErrorResponse("Scraping batch request exceeds $limit bytes", "PAYLOAD_TOO_LARGE"))
            return
        }
        filterChain.doFilter(CachedBodyHttpServletRequest(request, body), response)
    }

    private companion object {
        val BATCH_PATH = Regex("^/scraping/runs/[^/]+/batches$")
    }
}
