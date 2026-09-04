package io.vaullet.ledger.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.1 description, generated from the controllers rather than hand-maintained.
 *
 * <p>springdoc reads the actual request mappings and Bean Validation constraints, so the published
 * contract cannot drift from the code the way a checked-in {@code openapi.yaml} does. ADR-011, §5
 * takes this further: the generated document is committed as a build artefact and diffed in CI, so
 * a pull request that breaks the contract fails rather than merges.
 *
 * <p>Declaring the bearer scheme here means "Authorize" in Swagger UI actually works, which is the
 * difference between docs people use and docs people skim.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearer-jwt";

    private static final String DESCRIPTION =
            """
            Atomic balance reservations — see ADR-004.

            Money is a decimal string with an explicit currency, never a JSON number (ADR-011).
            Timestamps are RFC 3339 UTC. Errors are RFC 9457 problem+json carrying a stable `code`.

            Environment: %s""";

    @Bean
    OpenAPI apiDefinition(ApplicationProperties properties) {
        return new OpenAPI()
                .info(new Info()
                        .title("Vaullet Ledger API")
                        // The major version in the path (ADR-011). Compatible additions do not
                        // change it; a breaking change means /v2 and a 12-month deprecation window.
                        .version("1")
                        .description(DESCRIPTION.formatted(properties.environment()))
                        .contact(new Contact().name("Platform team").email("platform@vaullet.io")))
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("OAuth2 access token issued by the platform identity provider.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
