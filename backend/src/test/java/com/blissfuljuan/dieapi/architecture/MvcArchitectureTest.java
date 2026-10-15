package com.blissfuljuan.dieapi.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Model-View-Controller, enforced for every module, present and future.
 *
 * <pre>
 * com.blissfuljuan.dieapi.&lt;module&gt;
 *     controller/  C  HTTP only: routing, request validation, calling a service, returning a view
 *     view/        V  what clients receive (response representations): plain data, no logic, no dependencies
 *     model/       M  domain data (rows, commands, rules) the model layer works with
 *     service/     M  business rules; orchestrates repositories and other services
 *     repository/  M  persistence (SQL)
 * </pre>
 * {@code config} (wiring) and {@code common} (shared helpers, global error handling) are the only packages
 * outside the layout. A new module that does not follow it fails this build.
 */
@AnalyzeClasses(packages = "com.blissfuljuan.dieapi", importOptions = ImportOption.DoNotIncludeTests.class)
class MvcArchitectureTest {

    private static final String BASE = "com.blissfuljuan.dieapi";
    private static final Set<String> LAYERS = Set.of("controller", "view", "model", "service", "repository");
    private static final Set<String> UNLAYERED_PACKAGES = Set.of("config", "common");

    // ---- structure ----

    @ArchTest
    static final ArchRule everyModuleUsesTheMvcPackageLayout = classes()
        .that(inModulePackages())
        .should(liveInALayerPackage());

    @ArchTest
    static final ArchRule controllersLiveInControllerPackages = classes()
        .that().areAnnotatedWith(RestController.class)
        .or().areAnnotatedWith(Controller.class)
        .or().areAnnotatedWith(ControllerAdvice.class)
        .or().areAnnotatedWith(RestControllerAdvice.class)
        .should().resideInAPackage("..controller..");

    @ArchTest
    static final ArchRule servicesLiveInServicePackages = classes()
        .that().areAnnotatedWith(Service.class)
        .should().resideInAPackage("..service..");

    @ArchTest
    static final ArchRule repositoriesLiveInRepositoryPackages = classes()
        .that().areAnnotatedWith(Repository.class)
        .should().resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule businessComponentsLiveInServicePackages = classes()
        .that().areAnnotatedWith(Component.class)
        .and(inModulePackages())
        .should().resideInAPackage("..service..");

    // ---- dependencies between layers ----

    @ArchTest
    static final ArchRule layersDependOnlyInTheAllowedDirection = layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .layer("Controller").definedBy("..controller..")
        .layer("Service").definedBy("..service..")
        .layer("Repository").definedBy("..repository..")
        .layer("Model").definedBy("..model..")
        .layer("View").definedBy("..view..")
        // nothing depends on a controller
        .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
        // only controllers and services call services
        .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Service")
        // only services touch persistence
        .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Repository")
        // views are read by whoever builds or returns a response, never by model data
        .whereLayer("View").mayOnlyBeAccessedByLayers("Controller", "Service", "Repository", "View")
        // model data is what services, repositories and controllers (request commands) exchange; views stay independent of it
        .whereLayer("Model").mayOnlyBeAccessedByLayers("Controller", "Service", "Repository", "Model");

    @ArchTest
    static final ArchRule controllersDoNotTouchPersistence = noClasses()
        .that().resideInAPackage("..controller..")
        .should().dependOnClassesThat().resideInAnyPackage("..repository..", "org.springframework.jdbc..", "javax.sql..");

    @ArchTest
    static final ArchRule viewsArePlainData = noClasses()
        .that().resideInAPackage("..view..")
        .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..service..", "..repository..", "..model..", "org.springframework..");

    @ArchTest
    static final ArchRule modelDataIsPlain = noClasses()
        .that().resideInAPackage("..model..")
        .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..service..", "..repository..", "..view..");

    @ArchTest
    static final ArchRule repositoriesDoNotCallServices = noClasses()
        .that().resideInAPackage("..repository..")
        .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..");

    @ArchTest
    static final ArchRule transactionsAreManagedByTheModelLayerNotTheControllers = noMethods()
        .that().areDeclaredInClassesThat().resideInAPackage("..controller..")
        .should().beAnnotatedWith(Transactional.class);

    // ---- what controllers hand back ----

    @ArchTest
    static final ArchRule controllersReturnViewsNotModelData = methods()
        .that().areMetaAnnotatedWith(RequestMapping.class)      // every endpoint (@GetMapping, @PostMapping, ...)
        .and().areDeclaredInClassesThat().resideInAPackage("..controller..")
        .should(notReturnModelRepositoryOrServiceTypes());

    // ---- helpers ----

    private static DescribedPredicate<JavaClass> inModulePackages() {
        return new DescribedPredicate<>("reside in a feature module package") {
            @Override
            public boolean test(JavaClass c) {
                return moduleOf(c) != null;
            }
        };
    }

    /** First package segment below the base package, or null for the root package and the unlayered packages. */
    private static String moduleOf(JavaClass c) {
        String pkg = c.getPackageName();
        if (!pkg.startsWith(BASE + ".")) return null;
        String module = pkg.substring(BASE.length() + 1).split("\\.")[0];
        return UNLAYERED_PACKAGES.contains(module) ? null : module;
    }

    private static ArchCondition<JavaClass> liveInALayerPackage() {
        return new ArchCondition<>("live in <module>.{controller|view|model|service|repository}") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                String[] parts = c.getPackageName().substring(BASE.length() + 1).split("\\.");
                boolean ok = parts.length >= 2 && LAYERS.contains(parts[1]);
                if (!ok) {
                    events.add(SimpleConditionEvent.violated(c, c.getName() + " is not in a controller/view/model/service/repository package of module '" + parts[0] + "'"));
                }
            }
        };
    }

    private static ArchCondition<JavaMethod> notReturnModelRepositoryOrServiceTypes() {
        return new ArchCondition<>("not return model, repository or service types") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                method.getReturnType().getAllInvolvedRawTypes().forEach(type -> {
                    String pkg = type.getPackageName();
                    if (pkg.startsWith(BASE) && (pkg.contains(".model") || pkg.contains(".repository") || pkg.contains(".service"))) {
                        events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " returns " + type.getName() + "; controllers return view types"));
                    }
                });
            }
        };
    }
}
