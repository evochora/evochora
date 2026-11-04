package org.evochora.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;

/**
 * Keeps the running node's output in its log.
 * <p>
 * A node runs where nobody watches its console: what it writes to {@code System.out},
 * {@code System.err} or the {@code Console} of {@code System.console()} carries no time, level or
 * logger, does not reach the JSON log and cannot be filtered. Everything that runs inside the node
 * therefore reports through SLF4J, without exception. The command line interface, in {@code cli},
 * writes to the console before a node runs or instead of one: the commands that run once for a
 * user their results, the node commands an error in their arguments or checkpoint before the node
 * starts. So does the launcher's check, which runs before there is any logging. Neither lies in
 * the packages checked here.
 * <p>
 * The import options are those of {@link PackageDependencyRulesTest}, so that the classes are
 * imported once for both.
 */
@AnalyzeClasses(packages = "org.evochora", importOptions = {ImportOption.DoNotIncludeTests.class, ExcludeTestFixturesAndJmh.class})
@ArchTag("integration")
class ConsoleOutputRulesTest {

    private static final String ROOT = "org.evochora";

    @ArchTest
    static void theNodeWritesNothingToTheConsole(JavaClasses classes) {
        noClasses().that().resideInAnyPackage(
                        ROOT + ".node..",
                        ROOT + ".datapipeline..",
                        ROOT + ".runtime..",
                        ROOT + ".compiler..")
                .should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .orShould().callMethod(System.class, "console")
                .because("the running node reports through SLF4J, where its log is read")
                .check(classes);
    }
}
