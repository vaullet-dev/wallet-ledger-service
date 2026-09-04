package io.vaullet.ledger.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web-layer wiring that has no property equivalent.
 *
 * <p>Only CORS lives here. Notably <em>absent</em> is the {@code spring.mvc.apiversion.*} block the
 * {@code @vaullet-io} template configures: ADR-011 chose the major version in the URI path
 * ({@code /v1/reservations}) and explicitly rejected header versioning, on the grounds that a path
 * is greppable in logs and trivially testable by hand while a missing header is an implicit
 * version nobody notices. The template's header strategy is the deviation here, not this file.
 *
 * <p>Implementing {@link WebMvcConfigurer} <em>adds</em> to Boot's auto-configuration. Putting
 * {@code @EnableWebMvc} on it would replace that auto-configuration wholesale and quietly cost you
 * content negotiation, message converters and error handling — a classic Spring Boot own-goal.
 */
@Configuration(proxyBeanMethods = false)
// @ConfigurationPropertiesScan on the application class covers the running application, but a
// @WebMvcTest slice does not scan. Declaring the binding the class actually needs keeps the slice
// self-sufficient — a small habit that avoids "works in prod, fails in the slice test" wiring bugs.
@EnableConfigurationProperties(ApplicationProperties.class)
class WebMvcConfig implements WebMvcConfigurer {

    private final ApplicationProperties properties;

    WebMvcConfig(ApplicationProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.api().allowedOrigins();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/v1/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("*")
                .exposedHeaders("Location")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
