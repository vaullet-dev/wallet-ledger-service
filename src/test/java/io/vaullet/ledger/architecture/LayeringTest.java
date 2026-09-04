package io.vaullet.ledger.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures;
import com.tngtech.archunit.library.GeneralCodingRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

/**
 * The layering rules, enforced by the build instead of by code review.
 *
 * <p>Architecture documented only in a README decays: the first pull request that injects a
 * repository into a controller because it was quicker will be approved by someone in a hurry, and
 * after three of those the layers exist in name only. These tests fail that pull request instead.
 *
 * <p>The rules are deliberately few. A rule nobody can justify gets suppressed rather than obeyed,
 * so each one here is a boundary that would actually cost something to lose.
 */
@AnalyzeClasses(packages = "io.vaullet.ledger", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringTest {

    @ArchTest
    static final ArchRule layers_are_respected = Architectures.layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("API")
            .definedBy("io.vaullet.ledger..api..")
            .layer("Service")
            .definedBy("io.vaullet.ledger..service..")
            .layer("DAO")
            .definedBy("io.vaullet.ledger..dao..")
            .whereLayer("API")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Service")
            .mayOnlyBeAccessedByLayers("API")
            .whereLayer("DAO")
            .mayOnlyBeAccessedByLayers("Service");

    /**
     * The single most common shortcut: a controller that "just needs one query". It couples the HTTP
     * contract to the schema and puts the transaction boundary in the wrong place.
     *
     * <p>On this service the second half is the dangerous one. ADR-004's whole argument is that the
     * check and the write happen inside one transaction that locks the account row first; a
     * controller holding a repository would be free to read a balance outside that transaction and
     * act on it, which is precisely the mistake ADR-001 made.
     */
    @ArchTest
    static final ArchRule controllers_do_not_reach_into_repositories = noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(Repository.class)
            .because("controllers must go through the service layer, which owns transactions and rules");

    /**
     * Replaces the template's "JPA entities stay in the DAO layer" rule, which cannot apply here:
     * ADR-004 rejected an ORM, so there are no entities to keep in.
     *
     * <p>The equivalent boundary for a JDBC service is that nothing outside {@code dao} touches
     * {@code JdbcTemplate}. It is worth enforcing for the same reason the entity rule is: a stray
     * query in a service method is a statement that escaped review, and on this codebase the
     * statements <em>are</em> the design.
     */
    @ArchTest
    static final ArchRule jdbc_stays_in_the_dao_layer = noClasses()
            .that()
            .resideOutsideOfPackage("io.vaullet.ledger..dao..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework.jdbc..")
            .because("every statement the ledger issues must be readable in one place (ADR-004)");

    /**
     * Keeps the web stack out of the rules. ADR-004's revised flow settles and releases from a Kafka
     * listener, so a service method that reached for a servlet type would not be callable from the
     * path that actually settles money.
     */
    @ArchTest
    static final ArchRule the_service_layer_knows_nothing_about_http = noClasses()
            .that()
            .resideInAPackage("io.vaullet.ledger..service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("jakarta.servlet..", "org.springframework.web..", "org.springframework.http..")
            .because("the same rules are driven by a message listener, which has no request to bind");

    @ArchTest
    static final ArchRule no_field_injection = noFields()
            .should()
            .beAnnotatedWith(Autowired.class)
            .because("constructor injection makes dependencies explicit, final, and testable without a container");

    @ArchTest
    static final ArchRule no_standard_streams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule no_java_util_logging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule no_joda_or_legacy_date_api = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JODATIME
            .because("java.time is the only date API this codebase uses");
}
