package com.mshykhov.jobhunter.infrastructure.config

import com.mshykhov.jobhunter.infrastructure.security.OidcProperties
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.OAuthFlow
import io.swagger.v3.oas.models.security.OAuthFlows
import io.swagger.v3.oas.models.security.Scopes
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig(private val oidcProperties: OidcProperties) {
    @Bean
    fun openApi(): OpenAPI {
        val openAPI =
            OpenAPI()
                .info(
                    Info()
                        .title("Job Hunter API")
                        .description("Job vacancy monitoring and tracking system")
                        .version("0.1.0")
                        .contact(Contact().name("mshykhov").url("https://github.com/mshykhov-space/job-hunter")),
                )

        val issuer = oidcProperties.issuers.firstOrNull()?.trimEnd('/')
        if (oidcProperties.enabled && issuer != null) {
            // Authentik serves authorize/token globally, one level above the
            // per-application slug issuer (https://.../application/o/{slug}/).
            val oauthBase = issuer.substringBeforeLast('/')
            openAPI
                .addSecurityItem(SecurityRequirement().addList("oauth2"))
                .components(
                    Components().addSecuritySchemes(
                        "oauth2",
                        SecurityScheme()
                            .type(SecurityScheme.Type.OAUTH2)
                            .flows(
                                OAuthFlows().authorizationCode(
                                    OAuthFlow()
                                        .authorizationUrl("$oauthBase/authorize/")
                                        .tokenUrl("$oauthBase/token/")
                                        .scopes(
                                            Scopes()
                                                .addString("openid", "OpenID Connect")
                                                .addString("profile", "Profile and groups")
                                                .addString("email", "Email")
                                                .addString("job-hunter-api", "Job Hunter API audience and permissions"),
                                        ),
                                ),
                            ),
                    ),
                )
        }

        return openAPI
    }
}
