package org.evochora.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.evochora.runtime.model.FlatIndexCellVisitor;
import org.evochora.runtime.model.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Enforces that the environment's change tracking has one observer in production code.
 * <p>
 * The environment records which cells changed since the last sample and since the last snapshot,
 * and the observer that takes samples resets those records. A second reader of the changed cells
 * would see whatever the first one had not yet reset, and a second caller of the marks would take
 * changes away from the first — neither is detectable in the produced data. The engine creates
 * exactly one encoder, and this rule makes that the only class allowed to touch the four methods,
 * so a second observer surfaces as a failing build instead of as silently incomplete deltas.
 */
@Tag("unit")
class ChangeTrackingObserverRulesTest {

    private static final String OBSERVER = "org.evochora.datapipeline.utils.delta.DeltaCodec$Encoder";

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("test-fixtures"))
                .withImportOption(location -> !location.contains("-jmh"))
                .importPackages("org.evochora");
    }

    @Test
    void onlyTheEncoderReadsTheChangedCells() {
        noClasses().that().doNotHaveFullyQualifiedName(OBSERVER)
                .should().callMethod(Environment.class, "forEachCellChangedSinceLastSample", FlatIndexCellVisitor.class)
                .orShould().callMethod(Environment.class, "forEachCellChangedSinceLastSnapshot", FlatIndexCellVisitor.class)
                .check(productionClasses);
    }

    @Test
    void onlyTheEncoderTakesSamplesAndSnapshots() {
        noClasses().that().doNotHaveFullyQualifiedName(OBSERVER)
                .should().callMethod(Environment.class, "markSampleTaken")
                .orShould().callMethod(Environment.class, "markSnapshotTaken")
                .check(productionClasses);
    }
}
