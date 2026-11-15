package org.evochora.datapipeline.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.evochora.BuildInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * A run read by another build, or one whose revision is unknown on either side, gets a warning
 * through the caller's logger; a run written by this build, with both revisions known, gets none.
 */
@Tag("unit")
class BuildRevisionCheckTest {

    private final Logger log = mock(Logger.class);

    /** How this build names itself in the warning. */
    private static String reader() {
        return BuildInfo.describe(BuildInfo.version().orElse(""), BuildInfo.revision());
    }

    @Test
    void runWithoutRecordedRevision_Warns() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder().setSimulationRunId("run-a").build();

        BuildRevisionCheck.warnIfWrittenByAnotherBuild(metadata, log);

        verify(log).warn(anyString(), eq("run-a"), eq("a build of unknown revision"), eq(reader()),
            eq("the run does not name its sources"));
    }

    @Test
    void runWrittenByABuildWithoutVersion_NamesItsRevisionAndItsSources() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("run-b")
            .setBuildRevision("0123abcd")
            .build();

        BuildRevisionCheck.warnIfWrittenByAnotherBuild(metadata, log);

        verify(log).warn(anyString(), eq("run-b"), eq("a build of revision 0123abcd"), eq(reader()),
            eq("its sources are `git checkout 0123abcd`"));
    }

    @Test
    void runWrittenByARelease_NamesItsVersionAndAdvisesThatRelease() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("run-e")
            .setBuildRevision("0123abcd")
            .setBuildVersion("v1.0.0")
            .build();

        BuildRevisionCheck.warnIfWrittenByAnotherBuild(metadata, log);

        verify(log).warn(anyString(), eq("run-e"), eq("Evochora v1.0.0 (0123abcd)"), eq(reader()),
            eq("use Evochora v1.0.0 to reproduce or continue it"));
    }

    @Test
    void runWrittenFromUncommittedChanges_SaysTheSourcesAreGone() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("run-d")
            .setBuildRevision("0123abcd" + BuildInfo.DIRTY_SUFFIX)
            .build();

        BuildRevisionCheck.warnIfWrittenByAnotherBuild(metadata, log);

        verify(log).warn(anyString(), eq("run-d"), eq("a build of revision 0123abcd-dirty"), eq(reader()),
            eq("those sources carried uncommitted changes and cannot be checked out again"));
    }

    @Test
    void runWrittenByABuildFromShippedSources_NamesTheCommitAndSaysTheyMayHaveChanged() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("run-f")
            .setBuildRevision("0123abcd" + BuildInfo.REBUILT_SUFFIX)
            .build();

        BuildRevisionCheck.warnIfWrittenByAnotherBuild(metadata, log);

        verify(log).warn(anyString(), eq("run-f"), eq("a build of revision 0123abcd-rebuilt"), eq(reader()),
            eq("its sources were shipped as those of `git checkout 0123abcd` and may have been changed before the build"));
    }

    @Test
    void runWrittenByThisBuild_IsSilentWhenTheRevisionIsKnown() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("run-c")
            .setBuildRevision(BuildInfo.revision())
            .build();

        BuildRevisionCheck.warnIfWrittenByAnotherBuild(metadata, log);

        if (BuildInfo.UNKNOWN.equals(BuildInfo.revision())) {
            verify(log).warn(anyString(), eq("run-c"), eq("a build of unknown revision"), eq(reader()), anyString());
        } else {
            verify(log, never()).warn(anyString(), any(Object[].class));
        }
    }
}
