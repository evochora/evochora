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
 * A node runs where nobody watches its console: what it writes to {@code System.out} or
 * {@code System.err} carries no time, level or logger, does not reach the JSON log and cannot be
 * filtered. Everything that runs inside the node therefore reports through SLF4J. The commands
 * that run once for a user, in {@code cli}, may write their results to the console, and so may the
 * launcher's check, which runs before there is any logging; neither lies in the packages checked
 * here.
 * <p>
 * The one exception is {@code Node}, which writes to the console only when the logging framework
 * itself cannot be loaded or has already been closed, so that the message is not lost.
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
                .and().doNotHaveFullyQualifiedName(ROOT + ".node.Node")
                .should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .because("the running node reports through SLF4J, where its log is read")
                .check(classes);
    }
}
