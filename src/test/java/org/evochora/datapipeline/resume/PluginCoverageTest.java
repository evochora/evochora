package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.evochora.runtime.spi.ISimulationPlugin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@link ResumeNeutralityTest} in step with the plugins that exist.
 * <p>
 * A plugin carries state across a resume like any other part of the simulation, so leaving one out
 * of the neutrality run means its state is never proven to survive. Reading the plugin list from a
 * configuration file would make the test depend on settings that change for unrelated reasons, so
 * the neutrality test names its plugins explicitly — and this test makes sure that list is complete
 * by looking at the code rather than at any configuration.
 * <p>
 * A new plugin therefore fails here until it is added to the neutrality run. If one genuinely cannot
 * take part, exclude it below with the reason, so that the exception is visible rather than implied
 * by its absence.
 */
@Tag("unit")
class PluginCoverageTest {

    /**
     * Plugins deliberately left out of the neutrality run. Empty: every plugin can take part today.
     * An entry here needs a reason, because it narrows what resume neutrality proves.
     */
    private static final List<String> EXCLUDED = List.of();

    @Test
    void everyPluginIsCoveredByTheNeutralityRun() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("test-fixtures"))
                .withImportOption(location -> !location.contains("-jmh"))
                .importPackages("org.evochora");

        List<String> uncovered = productionClasses.stream()
                .filter(c -> c.isAssignableTo(ISimulationPlugin.class))
                .filter(c -> !c.isInterface())
                .filter(c -> !c.getModifiers().contains(JavaModifier.ABSTRACT))
                .map(JavaClass::getFullName)
                .filter(name -> !EXCLUDED.contains(name))
                .filter(name -> !ResumeNeutralityTest.PLUGINS_JSON.contains(name))
                .sorted()
                .toList();

        assertThat(uncovered)
                .as("plugins missing from ResumeNeutralityTest.PLUGINS_JSON — add them there, or "
                        + "exclude them in this test with a reason")
                .isEmpty();
    }

    /**
     * The counterpart: a plugin listed in the neutrality run must still exist. Otherwise the run
     * fails on an unresolvable class name, which says nothing about what actually went wrong.
     */
    @Test
    void everyConfiguredPluginStillExists() {
        JavaClasses productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("org.evochora");

        List<String> existing = productionClasses.stream()
                .filter(c -> c.isAssignableTo(ISimulationPlugin.class))
                .map(JavaClass::getFullName)
                .toList();

        List<String> configured = ResumeNeutralityTest.PLUGINS_JSON.lines()
                .filter(line -> line.contains("\"className\""))
                .map(line -> line.substring(line.indexOf("\"org.evochora") + 1))
                .map(line -> line.substring(0, line.indexOf('"')))
                .toList();

        assertThat(configured).as("the neutrality run must configure at least one plugin").isNotEmpty();
        assertThat(existing).as("classes named in the neutrality run").containsAll(configured);
    }
}
