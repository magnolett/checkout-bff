package io.github.magnolett.checkoutbff;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The boundaries the README promises, checked on every build: the checkout rules know no framework, the web layer
 * knows no adapter, the adapters know no use case, and the subscription consumer knows only the published event.
 */
@AnalyzeClasses(packages = "io.github.magnolett.checkoutbff", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPlainJava = classes()
            .that().resideInAPackage("..checkout.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("..checkout.domain..", "java..");

    @ArchTest
    static final ArchRule portsSpeakOnlyDomainAndReactor = classes()
            .that().resideInAPackage("..checkout.port..")
            .should().onlyDependOnClassesThat()
            .resideInAnyPackage("..checkout.domain..", "..checkout.port..", "java..", "reactor.core.publisher..");

    @ArchTest
    static final ArchRule useCasesIgnoreWebAndAdapters = noClasses()
            .that().resideInAPackage("..checkout.application..")
            .should().dependOnClassesThat().resideInAnyPackage("..bff..", "..integration..", "..subscription..");

    @ArchTest
    static final ArchRule webLayerDoesNotKnowTheAdapters = noClasses()
            .that().resideInAPackage("..bff..")
            .should().dependOnClassesThat().resideInAnyPackage("..integration..", "..subscription..");

    @ArchTest
    static final ArchRule adaptersDoNotReachUseCasesOrWeb = noClasses()
            .that().resideInAPackage("..integration..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..checkout.application..", "..bff..", "..subscription..");

    @ArchTest
    static final ArchRule subscriptionKnowsOnlyTheEventContract = noClasses()
            .that().resideInAPackage("..subscription..")
            .should().dependOnClassesThat().resideInAnyPackage("..checkout..", "..bff..", "..integration..");

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles = slices()
            .matching("io.github.magnolett.checkoutbff.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule checkoutLayersAreFreeOfCycles = slices()
            .matching("io.github.magnolett.checkoutbff.checkout.(*)..")
            .should().beFreeOfCycles();
}
