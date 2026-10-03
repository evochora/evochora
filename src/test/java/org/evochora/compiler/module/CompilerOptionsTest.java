package org.evochora.compiler.module;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CompilerOptions}.
 */
public class CompilerOptionsTest {

    @Test
    @Tag("unit")
    void defaults_hasSingleDefaultRoot() {
        CompilerOptions opts = CompilerOptions.defaults();
        assertThat(opts.sourceRoots()).hasSize(1);
        assertThat(opts.sourceRoots().get(0).path()).isEqualTo(".");
        assertThat(opts.sourceRoots().get(0).isDefault()).isTrue();
        assertThat(opts.defines()).isEmpty();
    }

    @Test
    @Tag("unit")
    void defines_namesAreUpperCased() {
        CompilerOptions opts = new CompilerOptions(List.of(), Map.of("aggressive", OptionalInt.empty(), "Redundancy", OptionalInt.of(2)));
        assertThat(opts.defines()).containsOnly(
                Map.entry("AGGRESSIVE", OptionalInt.empty()),
                Map.entry("REDUNDANCY", OptionalInt.of(2)));
    }

    @Test
    @Tag("unit")
    void defines_areCopiedAndUnmodifiable() {
        Map<String, OptionalInt> source = new HashMap<>();
        source.put("X", OptionalInt.of(1));
        CompilerOptions opts = new CompilerOptions(List.of(), source);
        source.put("Y", OptionalInt.of(2));

        assertThat(opts.defines()).containsOnlyKeys("X");
        assertThatThrownBy(() -> opts.defines().put("Z", OptionalInt.empty()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @Tag("unit")
    void defines_twoKeysOfOneName_failNamingBoth() {
        Map<String, OptionalInt> source = new LinkedHashMap<>();
        source.put("a", OptionalInt.of(1));
        source.put("A", OptionalInt.of(2));
        assertThatThrownBy(() -> new CompilerOptions(List.of(), source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'a'")
                .hasMessageContaining("'A'");
    }

    @Test
    @Tag("unit")
    void defines_differingOnlyInCase_areEqual() {
        CompilerOptions lower = new CompilerOptions(List.of(), Map.of("x", OptionalInt.of(1)));
        CompilerOptions upper = new CompilerOptions(List.of(), Map.of("X", OptionalInt.of(1)));
        assertThat(lower).isEqualTo(upper);
        assertThat(lower.hashCode()).isEqualTo(upper.hashCode());
    }

    @Test
    @Tag("unit")
    void sourceRootsOnly_hasNoDefines() {
        assertThat(new CompilerOptions(List.of(new SourceRoot(".", null))).defines()).isEmpty();
    }

    @Test
    @Tag("unit")
    void validate_singleDefault_succeeds() {
        CompilerOptions opts = new CompilerOptions(List.of(new SourceRoot(".", null)));
        opts.validate();
    }

    @Test
    @Tag("unit")
    void validate_noDefault_succeeds() {
        CompilerOptions opts = new CompilerOptions(List.of(
                new SourceRoot("./pred", "PRED")));
        opts.validate();
    }

    @Test
    @Tag("unit")
    void validate_multipleDefaults_fails() {
        CompilerOptions opts = new CompilerOptions(List.of(
                new SourceRoot(".", null),
                new SourceRoot("./other", null)));
        assertThatThrownBy(opts::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @Tag("unit")
    void validate_duplicatePrefix_fails() {
        CompilerOptions opts = new CompilerOptions(List.of(
                new SourceRoot(".", null),
                new SourceRoot("./a", "PRED"),
                new SourceRoot("./b", "PRED")));
        assertThatThrownBy(opts::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @Tag("unit")
    void validate_mixedRoots_succeeds() {
        CompilerOptions opts = new CompilerOptions(List.of(
                new SourceRoot(".", null),
                new SourceRoot("./pred", "PRED"),
                new SourceRoot("./prey", "PREY")));
        opts.validate();
    }

    @Test
    @Tag("unit")
    void validate_onlyPrefixed_succeeds() {
        CompilerOptions opts = new CompilerOptions(List.of(
                new SourceRoot("./pred", "PRED"),
                new SourceRoot("./prey", "PREY")));
        opts.validate();
    }

    @Test
    @Tag("unit")
    void validate_emptyStringPrefix_treatedAsDefault() {
        CompilerOptions opts = new CompilerOptions(List.of(
                new SourceRoot(".", null),
                new SourceRoot("./other", "")));
        assertThatThrownBy(opts::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @Tag("unit")
    void validate_singleCharPrefix_fails() {
        CompilerOptions opts = new CompilerOptions(List.of(
                new SourceRoot("./a", "A")));
        assertThatThrownBy(opts::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    @Tag("unit")
    void validate_lowercasePrefix_fails() {
        CompilerOptions opts = new CompilerOptions(List.of(
                new SourceRoot("./a", "pred")));
        assertThatThrownBy(opts::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid");
    }
}
