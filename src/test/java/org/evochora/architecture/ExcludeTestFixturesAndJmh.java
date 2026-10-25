package org.evochora.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/**
 * Leaves the test fixtures and the JMH sources out of the classes the architecture rules check.
 * <p>
 * Both live under {@code org.evochora} like production code, but reach the classpath as jars
 * rather than class directories, so {@link ImportOption.DoNotIncludeTests} does not catch them.
 * They are excluded by location instead. Every rule class names this option together with
 * {@code DoNotIncludeTests} and the package {@code org.evochora} in its {@code @AnalyzeClasses}, so
 * that ArchUnit imports the production classes once and every rule class reuses that import.
 */
public final class ExcludeTestFixturesAndJmh implements ImportOption {

    @Override
    public boolean includes(Location location) {
        return !location.contains("test-fixtures") && !location.contains("-jmh");
    }
}
