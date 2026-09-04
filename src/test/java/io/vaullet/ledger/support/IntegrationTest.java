package io.vaullet.ledger.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * One annotation for "full application, real database, real security".
 *
 * <p>A composed annotation rather than four annotations copy-pasted onto every test class. Beyond
 * brevity, it guarantees that every integration test declares an <em>identical</em> context
 * configuration — and Spring's test framework caches contexts by that configuration. One stray
 * {@code @TestPropertySource} on a single class silently doubles the number of application contexts
 * a build starts, which is the usual reason a test suite is slow. On this service that cost is
 * paid twice, because each new context also waits on Flyway.
 *
 * <p>{@code webEnvironment = MOCK} plus {@code MockMvc} exercises the entire servlet stack —
 * filters, security, argument resolution, message conversion, exception handling — without binding
 * a port. Use {@code RANDOM_PORT} only when a test genuinely needs real network behaviour.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
public @interface IntegrationTest {}
