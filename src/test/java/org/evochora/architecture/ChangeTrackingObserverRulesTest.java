package org.evochora.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import org.evochora.runtime.model.FlatIndexCellVisitor;
import org.evochora.runtime.model.Environment;

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
@AnalyzeClasses(packages = "org.evochora", importOptions = {ImportOption.DoNotIncludeTests.class, ExcludeTestFixturesAndJmh.class})
@ArchTag("integration")
class ChangeTrackingObserverRulesTest {

    private static final String OBSERVER = "org.evochora.datapipeline.utils.delta.DeltaCodec$Encoder";

    @ArchTest
    static void onlyTheEncoderReadsTheChangedCells(JavaClasses classes) {
        noClasses().that().doNotHaveFullyQualifiedName(OBSERVER)
                .should().callMethod(Environment.class, "forEachCellChangedSinceLastSample", FlatIndexCellVisitor.class)
                .orShould().callMethod(Environment.class, "forEachCellChangedSinceLastSnapshot", FlatIndexCellVisitor.class)
                .check(classes);
    }

    @ArchTest
    static void onlyTheEncoderTakesSamplesAndSnapshots(JavaClasses classes) {
        noClasses().that().doNotHaveFullyQualifiedName(OBSERVER)
                .should().callMethod(Environment.class, "markSampleTaken")
                .orShould().callMethod(Environment.class, "markSnapshotTaken")
                .check(classes);
    }
}
