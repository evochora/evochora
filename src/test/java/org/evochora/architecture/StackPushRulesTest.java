package org.evochora.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Deque;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Enforces that the instruction set pushes onto an organism's stacks only through the organism.
 * <p>
 * The organism's data, location and call stacks are handed out live, and their depth limits are
 * enforced by {@code Organism.pushData}, {@code pushLocation} and {@code pushCallFrame}, which
 * fail the current instruction when a stack is full. A push straight onto the deque would grow
 * the stack past its limit; this rule makes such a push a failing build instead of a stack that
 * grows without bound at runtime.
 */
@Tag("unit")
class StackPushRulesTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("test-fixtures"))
                .withImportOption(location -> !location.contains("-jmh"))
                .importPackages("org.evochora.runtime");
    }

    @Test
    void instructionsPushOnlyThroughTheOrganism() {
        noClasses().that().resideInAnyPackage("org.evochora.runtime.isa..", "org.evochora.runtime.internal..")
                .should().callMethod(Deque.class, "push", Object.class)
                .orShould().callMethod(Deque.class, "addFirst", Object.class)
                .orShould().callMethod(Deque.class, "addLast", Object.class)
                .orShould().callMethod(Deque.class, "offerFirst", Object.class)
                .orShould().callMethod(Deque.class, "offerLast", Object.class)
                .check(productionClasses);
    }
}
