package org.evochora.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Enforces which top-level packages may reference which other top-level packages.
 * <p>
 * The permitted graph is:
 * <pre>
 * cli          -&gt;  node, datapipeline, compiler, runtime
 * node         -&gt;  datapipeline, runtime
 * datapipeline -&gt;  compiler, runtime
 * compiler     -&gt;  runtime
 * runtime      -&gt;  (nothing)
 * </pre>
 * This class is the authoritative statement of that graph. An import creating an edge the graph does
 * not permit fails a test here, naming the source class, the target class and the rule it violates.
 * The only ways forward are withdrawing the import or editing this class, and an edit to this class
 * is visible in review. The point is not to prevent dependencies but to make a new one surface as a
 * decision instead of disappearing into an import line, which is why the rules are kept short:
 * a long list that gets extended by reflex carries no signal.
 * <p>
 * {@code cli} sits at the top of the graph and may reference everything, so it has no rule of its
 * own. That it may not be referenced from anywhere follows from the four rules below, each of which
 * excludes it.
 * <p>
 * Rules are written in the direction of the graph — "whom may I use" — so that they read the same
 * way the graph above does.
 */
@Tag("unit")
class PackageDependencyRulesTest {

    /** Package prefix of the Java sources belonging to this project. */
    private static final String ROOT = "org.evochora";

    private static JavaClasses productionClasses;

    /**
     * Imports the compiled production classes once for all rules in this class.
     * <p>
     * The Protobuf messages generated from {@code src/main/proto} are included like any other
     * production code. What the generator emits is determined entirely by hand-written
     * {@code .proto} files — target package, class names and referenced types alike — so a
     * violation originating there is a violation of a {@code .proto} definition and is fixed at
     * that source.
     * <p>
     * Test code is excluded because it legitimately reaches across package boundaries; a rule
     * firing there constantly would end up being switched off. That includes the test fixtures and
     * the JMH sources: both live under {@code org.evochora} like production code, reach the
     * classpath as jars rather than class directories, and are therefore not caught by ArchUnit's
     * test filter. They are excluded by location instead.
     */
    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("test-fixtures"))
                .withImportOption(location -> !location.contains("-jmh"))
                .importPackages(ROOT);
    }

    /**
     * {@code runtime} is the base of the graph and references no other top-level package.
     * <p>
     * The simulation core carries nothing outward: every type it borrowed from another package
     * would be a type a reimplementation in another language has to carry along.
     */
    @Test
    void runtimeDependsOnNoOtherTopLevelPackage() {
        noClasses().that().resideInAPackage(ROOT + ".runtime..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".compiler..",
                        ROOT + ".datapipeline..",
                        ROOT + ".node..",
                        ROOT + ".cli..")
                .check(productionClasses);
    }

    /**
     * {@code compiler} may reference {@code runtime} only.
     * <p>
     * The dependency on the runtime is intended: the instruction set is what the compiler targets.
     * Everything else is above the compiler in the graph.
     */
    @Test
    void compilerDependsOnRuntimeOnly() {
        noClasses().that().resideInAPackage(ROOT + ".compiler..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".datapipeline..",
                        ROOT + ".node..",
                        ROOT + ".cli..")
                .check(productionClasses);
    }

    /**
     * {@code datapipeline} may reference {@code compiler} and {@code runtime}.
     * <p>
     * It must not reference {@code node}: process wrappers belong to {@code node} and domain logic
     * to {@code datapipeline}, so the adapter between the two lives on node's side.
     */
    @Test
    void datapipelineDependsOnCompilerAndRuntimeOnly() {
        noClasses().that().resideInAPackage(ROOT + ".datapipeline..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".node..",
                        ROOT + ".cli..")
                .check(productionClasses);
    }

    /**
     * {@code node} may reference {@code datapipeline} and {@code runtime}.
     * <p>
     * Node owns process lifecycles, not what runs inside them, and it has no reason to know the
     * compiler.
     */
    @Test
    void nodeDependsOnDatapipelineAndRuntimeOnly() {
        noClasses().that().resideInAPackage(ROOT + ".node..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".compiler..",
                        ROOT + ".cli..")
                .check(productionClasses);
    }

    /**
     * Every production class lives in one of the packages the graph describes.
     * <p>
     * The rules above name the packages a given package may <em>not</em> reference. A sixth
     * top-level package would appear in none of those lists and could therefore be referenced from
     * anywhere unnoticed — the graph would silently stop describing the system. This rule forces a
     * new package to be admitted to the graph before any code can live in it.
     */
    @Test
    void everyClassLivesInADeclaredTopLevelPackage() {
        classes().should().resideInAnyPackage(
                        ROOT,
                        ROOT + ".cli..",
                        ROOT + ".compiler..",
                        ROOT + ".datapipeline..",
                        ROOT + ".node..",
                        ROOT + ".runtime..")
                .check(productionClasses);
    }

    /**
     * The top-level packages form no cycle.
     * <p>
     * While the four rules above hold, this one cannot fail: the permitted graph is acyclic. It
     * guards a different moment — an edit to this class that adds an edge and closes a cycle
     * without anyone noticing.
     */
    @Test
    void topLevelPackagesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .check(productionClasses);
    }
}
