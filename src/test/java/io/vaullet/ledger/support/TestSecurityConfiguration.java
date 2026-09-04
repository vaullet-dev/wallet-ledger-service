package io.vaullet.ledger.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Satisfies the {@code JwtDecoder} dependency of the production security chain without contacting an
 * identity provider.
 *
 * <p>Tests authenticate with {@code SecurityMockMvcRequestPostProcessors.jwt()}, which injects an
 * already-decoded token straight into the security context, so this decoder is never actually
 * called — it exists only so that the <em>real</em> {@code SecurityConfig} can be assembled. That
 * matters: the tests then exercise the same filter chain, the same authority mapping and the same
 * deny-by-default rules that run in production, rather than a permissive test-only chain that would
 * make the security tests meaningless.
 *
 * <p>It throws rather than returning a stub token, so a test that accidentally sends a real
 * {@code Authorization: Bearer} header fails loudly instead of silently authenticating.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSecurityConfiguration {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            throw new UnsupportedOperationException(
                    "Tests must authenticate with SecurityMockMvcRequestPostProcessors.jwt(); "
                            + "no real token verification is configured.");
        };
    }
}
