package com.mshykhov.jobhunter.infrastructure.scraping

import com.fasterxml.jackson.databind.ObjectMapper
import com.mshykhov.jobhunter.application.scraping.ScrapingService
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ScrapingProperties::class)
class ScrapingConfig {
    @Bean
    fun scrapingSourceInitializer(service: ScrapingService): ApplicationRunner = ApplicationRunner { service.synchronizeEnabledSources() }

    @Bean
    fun scrapingBatchSizeFilter(
        properties: ScrapingProperties,
        objectMapper: ObjectMapper,
    ): FilterRegistrationBean<ScrapingBatchSizeFilter> =
        FilterRegistrationBean<ScrapingBatchSizeFilter>().apply {
            filter = ScrapingBatchSizeFilter(properties, objectMapper)
            addUrlPatterns("/scraping/*")
        }
}
