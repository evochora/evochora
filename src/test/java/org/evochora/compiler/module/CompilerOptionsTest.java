package org.evochora.compiler.module;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
