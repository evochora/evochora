package org.evochora.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;

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
 * <p>
 * The Protobuf messages generated from {@code src/main/proto} are checked like any other
 * production code. What the generator emits is determined entirely by hand-written
 * {@code .proto} files — target package, class names and referenced types alike — so a
 * violation originating there is a violation of a {@code .proto} definition and is fixed at
 * that source.
 * <p>
 * Test code is not checked because it legitimately reaches across package boundaries; a rule
 * firing there constantly would end up being switched off. {@link ExcludeTestFixturesAndJmh}
 * says why the test fixtures and the JMH sources need an option of their own.
 */
@AnalyzeClasses(packages = "org.evochora", importOptions = {ImportOption.DoNotIncludeTests.class, ExcludeTestFixturesAndJmh.class})
@ArchTag("integration")
class PackageDependencyRulesTest {

    /** Package prefix of the Java sources belonging to this project. */
    private static final String ROOT = "org.evochora";

    /**
     * {@code runtime} is the base of the graph and references no other top-level package.
     * <p>
     * The simulation core carries nothing outward: every type it borrowed from another package
     * would be a type a reimplementation in another language has to carry along.
     */
    @ArchTest
    static void runtimeDependsOnNoOtherTopLevelPackage(JavaClasses classes) {
        noClasses().that().resideInAPackage(ROOT + ".runtime..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".compiler..",
                        ROOT + ".datapipeline..",
                        ROOT + ".node..",
                        ROOT + ".cli..")
                .check(classes);
    }

    /**
     * {@code compiler} may reference {@code runtime} only.
     * <p>
     * The dependency on the runtime is intended: the instruction set is what the compiler targets.
     * Everything else is above the compiler in the graph.
     */
    @ArchTest
    static void compilerDependsOnRuntimeOnly(JavaClasses classes) {
        noClasses().that().resideInAPackage(ROOT + ".compiler..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".datapipeline..",
                        ROOT + ".node..",
                        ROOT + ".cli..")
                .check(classes);
    }

    /**
     * {@code datapipeline} may reference {@code compiler} and {@code runtime}.
     * <p>
     * It must not reference {@code node}: process wrappers belong to {@code node} and domain logic
     * to {@code datapipeline}, so the adapter between the two lives on node's side.
     */
    @ArchTest
    static void datapipelineDependsOnCompilerAndRuntimeOnly(JavaClasses classes) {
        noClasses().that().resideInAPackage(ROOT + ".datapipeline..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".node..",
                        ROOT + ".cli..")
                .check(classes);
    }

    /**
     * {@code node} may reference {@code datapipeline} and {@code runtime}.
     * <p>
     * Node owns process lifecycles, not what runs inside them, and it has no reason to know the
     * compiler.
     */
    @ArchTest
    static void nodeDependsOnDatapipelineAndRuntimeOnly(JavaClasses classes) {
        noClasses().that().resideInAPackage(ROOT + ".node..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".compiler..",
                        ROOT + ".cli..")
                .check(classes);
    }

    /**
     * Every production class lives in one of the packages the graph describes.
     * <p>
     * The rules above name the packages a given package may <em>not</em> reference. A sixth
     * top-level package would appear in none of those lists and could therefore be referenced from
     * anywhere unnoticed — the graph would silently stop describing the system. This rule forces a
     * new package to be admitted to the graph before any code can live in it.
     */
    @ArchTest
    static void everyClassLivesInADeclaredTopLevelPackage(JavaClasses classes) {
        classes().should().resideInAnyPackage(
                        ROOT,
                        ROOT + ".cli..",
                        ROOT + ".compiler..",
                        ROOT + ".datapipeline..",
                        ROOT + ".node..",
                        ROOT + ".runtime..")
                .check(classes);
    }

    /**
     * The top-level packages form no cycle.
     * <p>
     * While the four rules above hold, this one cannot fail: the permitted graph is acyclic. It
     * guards a different moment — an edit to this class that adds an edge and closes a cycle
     * without anyone noticing.
     */
    @ArchTest
    static void topLevelPackagesAreFreeOfCycles(JavaClasses classes) {
        slices().matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }
}
