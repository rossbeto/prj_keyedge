package dev.keyedge.testkit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The layering rules of masterplan §3.3, shared by every service. Each rule takes the service's
 * base package (e.g. {@code dev.keyedge.keys}) and is proven to fail on a fixture in this module's
 * tests.
 */
public final class KeyedgeArchitectureRules {
  /** Frameworks and libraries that must stay out of the core (app, domain, ports). */
  public static final List<String> FRAMEWORK_PACKAGES =
      List.of("io.micronaut..", "software.amazon..", "reactor..", "io.github.resilience4j..");

  /** Deliberately wrong code for "found and fixed" demos lives here and is exempt. */
  public static final String PITFALLS = "..lab.pitfalls..";

  private KeyedgeArchitectureRules() {}

  /** Production classes of one base package, without tests and pitfall demos. */
  public static JavaClasses importMain(String basePackage) {
    return new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .withImportOption(KeyedgeArchitectureRules::notPitfall)
        .importPackages(basePackage);
  }

  private static boolean notPitfall(Location location) {
    return !location.contains("/lab/pitfalls/");
  }

  /** api → app → (domain, ports) ← adapters; wiring may use everything; nobody uses wiring. */
  public static ArchRule layers(String base) {
    return layeredArchitecture()
        .consideringOnlyDependenciesInLayers()
        .withOptionalLayers(true)
        .layer("api")
        .definedBy(base + ".api..")
        .layer("app")
        .definedBy(base + ".app..")
        .layer("domain")
        .definedBy(base + ".domain..")
        .layer("ports")
        .definedBy(base + ".ports..")
        .layer("adapters")
        .definedBy(base + ".adapters..")
        .layer("wiring")
        .definedBy(base + ".wiring..")
        .whereLayer("wiring")
        .mayNotBeAccessedByAnyLayer()
        .whereLayer("api")
        .mayOnlyBeAccessedByLayers("wiring")
        .whereLayer("adapters")
        .mayOnlyBeAccessedByLayers("wiring")
        .whereLayer("app")
        .mayOnlyBeAccessedByLayers("api", "wiring")
        .whereLayer("ports")
        .mayOnlyBeAccessedByLayers("app", "adapters", "api", "wiring")
        .whereLayer("domain")
        .mayOnlyBeAccessedByLayers("app", "ports", "adapters", "api", "wiring");
  }

  /** Use cases, domain and ports never import a framework or an AWS/HTTP/reactive library. */
  public static ArchRule coreIsFrameworkFree(String base) {
    return noClasses()
        .that()
        .resideInAnyPackage(base + ".app..", base + ".domain..", base + ".ports..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(FRAMEWORK_PACKAGES.toArray(String[]::new))
        .because("the core is plain Java, testable with new and fakes (D-10)");
  }

  /** Time is injected: no clock reads outside the composition root (masterplan §3.3.3 rule 5). */
  public static ArchRule noClockReadsOutsideWiring(String base) {
    return noClasses()
        .that()
        .resideInAPackage(base + "..")
        .and()
        .resideOutsideOfPackage(base + ".wiring..")
        .should()
        .callMethod(Instant.class, "now")
        .orShould()
        .callMethod(System.class, "currentTimeMillis")
        .orShould()
        .callMethod(Clock.class, "systemUTC")
        .orShould()
        .callMethod(Clock.class, "systemDefaultZone")
        .because("time is injected as an InstantSource so tests control it");
  }

  /** No dumping-ground class names. */
  public static ArchRule noUtilsOrHelpers(String base) {
    return classes()
        .that()
        .resideInAPackage(base + "..")
        .should()
        .haveSimpleNameNotEndingWith("Utils")
        .andShould()
        .haveSimpleNameNotEndingWith("Helper")
        .allowEmptyShould(true);
  }

  /** Adapter packages do not know each other (one technology per adapter). */
  public static ArchRule adaptersIndependent(String base) {
    return slices()
        .matching(base + ".adapters.(*)..")
        .should()
        .notDependOnEachOther()
        .allowEmptyShould(true);
  }

  /**
   * A pure library depends only on the JDK, JSpecify and the packages it names (its own package is
   * always allowed): no framework, no AWS, no I/O library.
   */
  public static ArchRule pureLibrary(String base, String... alsoAllowed) {
    String[] allowed = new String[alsoAllowed.length + 4];
    allowed[0] = "java..";
    allowed[1] = "javax.crypto.."; // JDK crypto lives under javax
    allowed[2] = "org.jspecify..";
    allowed[3] = base + "..";
    System.arraycopy(alsoAllowed, 0, allowed, 4, alsoAllowed.length);
    return classes()
        .that()
        .resideInAPackage(base + "..")
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage(allowed)
        .because("shared libraries are plain Java (masterplan §3.3.1)");
  }

  /** No hidden randomness or time in a pure library: both are injected. */
  public static ArchRule noHiddenRandomnessOrTime(String base, String... exemptPackages) {
    var rule = noClasses().that().resideInAPackage(base + "..");
    for (String exempt : exemptPackages) {
      rule = rule.and().resideOutsideOfPackage(exempt);
    }
    return rule.should()
        .callConstructor(java.security.SecureRandom.class)
        .orShould()
        .callMethod(Instant.class, "now")
        .orShould()
        .callMethod(System.class, "currentTimeMillis")
        .orShould()
        .callMethod(java.time.InstantSource.class, "system")
        .because("time and randomness are injected so tests are deterministic")
        .allowEmptyShould(true);
  }

  /** All rules for a service, in one call. */
  public static List<ArchRule> all(String base) {
    return List.of(
        layers(base),
        coreIsFrameworkFree(base),
        noClockReadsOutsideWiring(base),
        noUtilsOrHelpers(base),
        adaptersIndependent(base));
  }

  /** Checks every rule and reports all violations together. */
  public static void checkAll(String base) {
    JavaClasses classes = importMain(base);
    for (ArchRule rule : all(base)) {
      rule.allowEmptyShould(true).check(classes);
    }
  }

  /** True when a class is a deliberate pitfall reproduction. */
  public static boolean isPitfall(JavaClass javaClass) {
    return javaClass.getPackageName().contains(".lab.pitfalls");
  }
}
