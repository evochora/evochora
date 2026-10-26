package org.evochora.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import java.util.Deque;

/**
 * Enforces that the instruction set pushes onto an organism's stacks only through the organism.
 * <p>
 * The organism's data, location and call stacks are handed out live, and their depth limits are
 * enforced by {@code Organism.pushData}, {@code pushLocation} and {@code pushCallFrame}, which
 * fail the current instruction when a stack is full. A push straight onto the deque would grow
 * the stack past its limit; this rule makes such a push a failing build instead of a stack that
 * grows without bound at runtime.
 */
@AnalyzeClasses(packages = "org.evochora", importOptions = {ImportOption.DoNotIncludeTests.class, ExcludeTestFixturesAndJmh.class})
@ArchTag("integration")
class StackPushRulesTest {

    @ArchTest
    static void instructionsPushOnlyThroughTheOrganism(JavaClasses classes) {
        noClasses().that().resideInAnyPackage("org.evochora.runtime.isa..", "org.evochora.runtime.internal..")
                .should().callMethod(Deque.class, "push", Object.class)
                .orShould().callMethod(Deque.class, "addFirst", Object.class)
                .orShould().callMethod(Deque.class, "addLast", Object.class)
                .orShould().callMethod(Deque.class, "offerFirst", Object.class)
                .orShould().callMethod(Deque.class, "offerLast", Object.class)
                .check(classes);
    }
}
