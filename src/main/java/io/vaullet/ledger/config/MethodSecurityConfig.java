package io.vaullet.ledger.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns on {@code @PreAuthorize} / {@code @PostAuthorize} for every profile, including
 * {@code local}.
 *
 * <p>Kept separate from the filter chains deliberately: a developer running locally should hit the
 * same authorisation rules as production, just with a principal that satisfies them (see
 * {@link LocalSecurityConfig}). Disabling method security outside production would mean the first
 * time anyone exercises those rules is in a deployed environment.
 *
 * <p>URL rules and method rules answer different questions — "may this request reach the app" versus
 * "may this principal perform this operation" — and a service that only has the former loses its
 * authorisation the moment a second entry point is added. That is not hypothetical here: ADR-004's
 * revised flow settles and releases from a Kafka listener, which never passes through a filter
 * chain at all.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class MethodSecurityConfig {}
