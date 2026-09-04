package org.evochora.compiler.module;

import org.evochora.compiler.features.importdir.ImportDependencyScanHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the pattern by which the dependency scan recognises an {@code .IMPORT} line.
 * <p>
 * A line this pattern misses is never scanned, so the module behind it is never loaded and the
 * parser never sees it; a marker it captures wrongly decides what a module re-exports. Both are
 * silent failures at the very front of the pipeline, which is why the syntax is pinned here in
 * isolation rather than only through programs that happen to use it. The pattern recognises a
 * line by the directive and the quoted path alone, so a directive whose clauses are malformed
 * is still loaded and its malformation is reported by the phases that read the clauses.
 */
class ImportPatternTest {

    private static final Pattern PATTERN = new ImportDependencyScanHandler().pattern();

    private static Matcher match(String line) {
        Matcher matcher = PATTERN.matcher(line);
        assertThat(matcher.matches()).as("'%s' is recognised as an import", line).isTrue();
        return matcher;
    }

    @Test
    @Tag("unit")
    void aPlainImportCarriesNoExportMarker() {
        Matcher matcher = match(".IMPORT \"modules/math.evo\" AS MATHLIB");

        assertThat(matcher.group(1)).isNull();
        assertThat(matcher.group(2)).isEqualTo("modules/math.evo");
    }

    @Test
    @Tag("unit")
    void theExportPrefixIsCaptured() {
        Matcher matcher = match("EXPORT .IMPORT \"modules/math.evo\" AS MATHLIB");

        assertThat(matcher.group(1)).isNotNull();
        assertThat(matcher.group(2)).isEqualTo("modules/math.evo");
    }

    @Test
    @Tag("unit")
    void usingClausesDoNotHideTheMarkerOrThePath() {
        Matcher plain = match(".IMPORT \"m.evo\" AS NAV USING MATHLIB AS ARITH");
        assertThat(plain.group(1)).isNull();
        assertThat(plain.group(2)).isEqualTo("m.evo");

        Matcher exported = match("EXPORT .IMPORT \"m.evo\" AS NAV USING MATHLIB AS ARITH USING B AS C");
        assertThat(exported.group(1)).isNotNull();
        assertThat(exported.group(2)).isEqualTo("m.evo");
    }

    @Test
    @Tag("unit")
    void theDirectiveIsRecognisedInLowerCase() {
        Matcher matcher = match("export .import \"m.evo\" as nav");

        assertThat(matcher.group(1)).isNotNull();
        assertThat(matcher.group(2)).isEqualTo("m.evo");
    }

    @Test
    @Tag("unit")
    void aDirectiveWithoutTheAliasClauseIsStillRecognised() {
        Matcher matcher = match(".IMPORT \"m.evo\"");

        assertThat(matcher.group(2)).isEqualTo("m.evo");
    }

    @Test
    @Tag("unit")
    void aDirectiveWithAMalformedUsingClauseIsStillRecognised() {
        Matcher matcher = match(".IMPORT \"m.evo\" AS NAV USING X");

        assertThat(matcher.group(2)).isEqualTo("m.evo");
    }

    @Test
    @Tag("unit")
    void aWordMerelyStartingWithExportIsNotTheMarker() {
        assertThat(PATTERN.matcher("EXPORTED .IMPORT \"m.evo\" AS NAV").matches()).isFalse();
    }

    @Test
    @Tag("unit")
    void theMarkerBelongsInFrontOfTheDirective() {
        assertThat(PATTERN.matcher(".IMPORT EXPORT \"m.evo\" AS NAV").matches()).isFalse();
    }
}
