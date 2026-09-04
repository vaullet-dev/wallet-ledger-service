package io.vaullet.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Developer-machine security: everything open, no identity provider required.
 *
 * <p>Active only under the {@code local} profile, which {@code spring-boot:run} sets for you (see
 * the {@code spring-boot-maven-plugin} block in {@code pom.xml}). A plain {@code java -jar} gets
 * {@link SecurityConfig} and therefore refuses to start without {@code OAUTH2_ISSUER_URI} — the
 * safe default is the one that applies in production.
 *
 * <p>{@link SecurityConfig} carries the mirror-image {@code @Profile("!local")}, so exactly one
 * filter chain is ever active.
 */
@Profile("local")
@Configuration(proxyBeanMethods = false)
class LocalSecurityConfig {

    @Bean
    SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Method security stays switched on, so the anonymous principal is handed the
                // authorities the @PreAuthorize rules ask for. Local development exercises the real
                // rules rather than a version of the app with authorisation compiled out.
                .anonymous(anonymous ->
                        anonymous.authorities("SCOPE_ledger:read", "SCOPE_ledger:write", "ROLE_OPERATOR"))
                .build();
    }
}
