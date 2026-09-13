package org.evochora;

import static org.assertj.core.api.Assertions.assertThat;

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
