package io.vaullet.ledger.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated configuration for everything this service owns.
 *
 * <p>Why a record and not {@code @Value} injection scattered across beans:
 *
 * <ul>
 *   <li><b>Fail fast.</b> {@code @Validated} runs Bean Validation at binding time, so a bad value
 *       stops the context from starting instead of blowing up on the first request that hits it.
 *   <li><b>Immutable.</b> Records give constructor binding for free — no setters, no half-built
 *       configuration objects, safe to share across threads.
 *   <li><b>Discoverable.</b> {@code spring-boot-configuration-processor} (wired up in the POM) turns
 *       this file into IDE auto-completion and inline docs for {@code application.yaml}.
 * </ul>
 *
 * <p>Deliberately small. The two settings that most services would expect to find here —
 * the default hold lifetime and its ceiling — are not here on purpose: ADR-004 puts the ceiling in
 * {@code ledger_config.max_hold_seconds}, in the database, alongside the money it governs and
 * inside the same transaction that reads it. Splitting hold policy across a YAML file and a table
 * would give the ledger two sources of truth for the same rule.
 *
 * @param environment free-form environment label, surfaced on {@code /actuator/info} and in the
 *     OpenAPI document
 * @param api inbound API behaviour
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record ApplicationProperties(@NotBlank String environment, @Valid @NotNull Api api) {

    /**
     * Inbound API behaviour.
     *
     * @param allowedOrigins browser origins allowed to call this API; empty disables CORS entirely,
     *     which is the right default for a service whose callers are other services (ADR-008)
     */
    public record Api(@NotNull List<String> allowedOrigins) {}
}
