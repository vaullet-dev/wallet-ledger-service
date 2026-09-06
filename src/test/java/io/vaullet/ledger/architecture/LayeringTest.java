package io.vaullet.ledger.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.vaullet.common.test.ArchitectureRules;

/**
 * The layering rules, enforced by the build instead of by code review.
 *
 * <p>Architecture documented only in a README decays: the first pull request that injects a
 * repository into a controller because it was quicker will be approved by someone in a hurry, and
 * after three of those the layers exist in name only. These tests fail that pull request instead.
 *
 * <p>The rules themselves live in {@code backend-common-test} and are shared with every other
 * {@code @vaullet-io} service, so this file is the two lines that point them at this codebase plus
 * the one rule that is genuinely local. A rule that is copied is a rule that gets edited locally,
 * and a boundary each service defines slightly differently is not a platform boundary at all.
 */
@AnalyzeClasses(packages = LayeringTest.BASE, importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringTest {

    static final String BASE = "io.vaullet.ledger";

    @ArchTest
    static final ArchRule layers_are_respected = ArchitectureRules.layersAreRespected(BASE);

    @ArchTest
    static final ArchRule controllers_do_not_reach_into_repositories =
            ArchitectureRules.controllersDoNotReachIntoRepositories();

    /**
     * The template's rule is "JPA entities stay in the DAO layer", which cannot apply here: ADR-004
     * rejected an ORM, so there are no entities to keep in. The equivalent boundary for a JDBC
     * service is that nothing outside {@code dao} touches {@code JdbcTemplate}, and it is worth
     * enforcing for the same reason — a stray query in a service method is a statement that escaped
     * review, and on this codebase the statements <em>are</em> the design.
     *
     * <p>Which is why the shared rule takes the persistence packages as an argument rather than
     * hard-coding {@code jakarta.persistence}: one boundary, two access technologies.
     */
    @ArchTest
    static final ArchRule jdbc_stays_in_the_dao_layer =
            ArchitectureRules.persistenceStaysInTheDaoLayer(BASE, "org.springframework.jdbc..");

    @ArchTest
    static final ArchRule the_service_layer_knows_nothing_about_http =
            ArchitectureRules.serviceLayerKnowsNothingAboutHttp(BASE);

    @ArchTest
    static final ArchRule no_field_injection = ArchitectureRules.noFieldInjection();

    @ArchTest
    static final ArchRule no_standard_streams = ArchitectureRules.noStandardStreams();

    @ArchTest
    static final ArchRule no_java_util_logging = ArchitectureRules.noJavaUtilLogging();

    @ArchTest
    static final ArchRule no_joda_or_legacy_date_api = ArchitectureRules.noLegacyDateApi();
}
