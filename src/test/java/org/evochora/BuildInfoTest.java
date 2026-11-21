package org.evochora;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Properties;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The revision a build records about itself, and when two revisions count as the same sources.
 */
@Tag("unit")
class BuildInfoTest {

    @Test
    void revisionOf_ReadsTheRecordedRevision() {
        Properties properties = new Properties();
        properties.setProperty("revision", " 9c80bde4-dirty ");

        assertThat(BuildInfo.revisionOf(properties)).isEqualTo("9c80bde4-dirty");
    }

    @Test
    void revisionOf_MissingOrBlankKey_IsUnknown() {
        Properties blank = new Properties();
        blank.setProperty("revision", "  ");

        assertThat(BuildInfo.revisionOf(new Properties())).isEqualTo(BuildInfo.UNKNOWN);
        assertThat(BuildInfo.revisionOf(blank)).isEqualTo(BuildInfo.UNKNOWN);
    }

    @Test
    void versionOf_ReadsTheRecordedVersionAndIsEmptyWithoutIt() {
        Properties properties = new Properties();
        properties.setProperty("version", " v0.4.0 ");
        Properties blank = new Properties();
        blank.setProperty("version", "");

        assertThat(BuildInfo.versionOf(properties)).contains("v0.4.0");
        assertThat(BuildInfo.versionOf(new Properties())).isEmpty();
        assertThat(BuildInfo.versionOf(blank)).isEmpty();
    }

    @Test
    void doiOf_ReadsTheRecordedDoiAndIsEmptyWithoutIt() {
        Properties properties = new Properties();
        properties.setProperty("doi", " 10.5281/zenodo.1234567 ");
        Properties blank = new Properties();
        blank.setProperty("doi", "");

        assertThat(BuildInfo.doiOf(properties)).contains("10.5281/zenodo.1234567");
        assertThat(BuildInfo.doiOf(new Properties())).isEmpty();
        assertThat(BuildInfo.doiOf(blank)).isEmpty();
    }

    @Test
    void versionLines_NameTheDoiInALineOfItsOwnAndOnlyWhereTheBuildHasOne() {
        assertThat(BuildInfo.versionLines(Optional.of("v1.0.0"), "9c80bde4", Optional.of("10.5281/zenodo.1234567")))
            .containsExactly("Evochora v1.0.0 (9c80bde4)", "DOI 10.5281/zenodo.1234567");
        assertThat(BuildInfo.versionLines(Optional.of("v1.0.0"), "9c80bde4", Optional.empty()))
            .containsExactly("Evochora v1.0.0 (9c80bde4)");
        assertThat(BuildInfo.versionLines(Optional.empty(), "9c80bde4", Optional.empty()))
            .containsExactly("Evochora (revision 9c80bde4)");
    }

    @Test
    void describe_NamesABuildByItsVersionOrByItsRevisionAlone() {
        assertThat(BuildInfo.describe("v1.0.0", "9c80bde4")).isEqualTo("Evochora v1.0.0 (9c80bde4)");
        assertThat(BuildInfo.describe("", "9c80bde4")).isEqualTo("a build of revision 9c80bde4");
        assertThat(BuildInfo.describe(null, "9c80bde4")).isEqualTo("a build of revision 9c80bde4");
        assertThat(BuildInfo.describe("", BuildInfo.UNKNOWN)).isEqualTo("a build of unknown revision");
    }

    @Test
    void matches_UnknownRevisionNeverMatches() {
        assertThat(BuildInfo.matches(BuildInfo.UNKNOWN)).isFalse();
        assertThat(BuildInfo.matches("")).isFalse();
    }

    @Test
    void matches_OwnRevisionMatchesExactlyWhenKnown() {
        boolean known = !BuildInfo.UNKNOWN.equals(BuildInfo.revision());

        assertThat(BuildInfo.matches(BuildInfo.revision())).isEqualTo(known);
        assertThat(BuildInfo.matches(BuildInfo.revision() + "x")).isFalse();
    }
}
