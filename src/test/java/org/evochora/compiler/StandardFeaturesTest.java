package org.evochora.compiler;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StandardFeatures}.
 */
@Tag("unit")
class StandardFeaturesTest {

    @Test
    void allReturnsExpectedCount() {
        assertThat(StandardFeatures.all()).hasSize(14);
    }

    @Test
    void allReturnsDistinctFeatures() {
        List<String> names = StandardFeatures.all().stream()
                .map(ICompilerFeature::name)
                .collect(Collectors.toList());

        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void allReturnsImmutableList() {
        List<ICompilerFeature> features = StandardFeatures.all();

        assertThatThrownBy(() -> features.add(new ICompilerFeature() {
            @Override public String name() { return "fake"; }
            @Override public void register(IFeatureRegistrationContext ctx) {}
        })).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyFeatureRegistersWithoutError() {
        for (ICompilerFeature feature : StandardFeatures.all()) {
            FeatureRegistry registry = new FeatureRegistry();
            feature.register(registry);
        }
    }

    @Test
    void defaultParserStatementHandlerIsRegistered() {
        FeatureRegistry registry = new FeatureRegistry();
        StandardFeatures.all().forEach(f -> f.register(registry));
        assertThat(registry.defaultParserStatementHandler()).isNotNull();
    }

    @Test
    void allFeaturesRegisterWithoutConflict() {
        FeatureRegistry registry = new FeatureRegistry();
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> StandardFeatures.all().forEach(f -> f.register(registry)));
    }
}
