package io.vaullet.ledger.config;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT resource-server security — the default posture for a backend service.
 *
 * <p>Modern practice this encodes:
 *
 * <ul>
 *   <li><b>Lambda DSL only.</b> {@code WebSecurityConfigurerAdapter} was removed in Spring Security
 *       6 and the non-lambda DSL in 7; a {@link SecurityFilterChain} bean is the only way now.
 *   <li><b>Deny by default.</b> The chain ends in {@code anyRequest().authenticated()}, so a new
 *       endpoint is protected the moment it is written. Opening a path is an explicit act.
 *   <li><b>No sessions, no CSRF.</b> A token-authenticated API creates no session, so there is no
 *       session-riding attack for CSRF to defend against. Disabling it on a <em>cookie</em>-
 *       authenticated app would be a real vulnerability — the distinction matters.
 *   <li><b>Authorities come from the token.</b> {@code SCOPE_*} from the standard {@code scope}
 *       claim plus {@code ROLE_*} from whatever claim the IdP puts roles in, so
 *       {@code @PreAuthorize("hasAuthority('SCOPE_ledger:write')")} works in the service layer.
 * </ul>
 *
 * <p>This chain is disabled under the {@code local} profile, where {@link LocalSecurityConfig} takes
 * over. It needs {@code OAUTH2_ISSUER_URI}; without a {@code JwtDecoder} the context fails to start.
 * That is intentional — a ledger that silently starts unauthenticated is worse than one that
 * refuses to boot.
 */
@Profile("!local")
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** Endpoints that must stay reachable without a token. Keep this list short and reviewed. */
    static final String[] PUBLIC_PATHS = {
        "/actuator/health", "/actuator/health/**", "/actuator/info",
        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"
    };

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_PATHS)
                        .permitAll()
                        // Pre-flight requests never carry credentials.
                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                        .permitAll()
                        // Everything the Actuator exposes beyond health/info is operator-only.
                        .requestMatchers("/actuator/**")
                        .hasRole("OPERATOR")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                // Do not leak a WWW-Authenticate browser prompt from a JSON API.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }

    /**
     * Maps JWT claims onto Spring Security authorities.
     *
     * <p>Scopes become {@code SCOPE_x} (Spring's default). Roles are read from a configurable claim
     * and become {@code ROLE_x}; adjust {@code ROLES_CLAIM} to match the identity provider —
     * Keycloak nests them under {@code realm_access.roles}, Auth0 uses a namespaced claim, Entra ID
     * uses {@code roles}.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        var scopes = new JwtGrantedAuthoritiesConverter();
        Converter<Jwt, Collection<GrantedAuthority>> combined = jwt -> Stream.concat(
                        scopes.convert(jwt).stream(), rolesFrom(jwt).stream())
                .distinct()
                .toList();

        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(combined);
        return converter;
    }

    private static final String ROLES_CLAIM = "roles";

    private static List<GrantedAuthority> rolesFrom(Jwt jwt) {
        var roles = jwt.getClaimAsStringList(ROLES_CLAIM);
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }
}
