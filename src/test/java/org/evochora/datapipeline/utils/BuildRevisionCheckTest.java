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
 * A run read by another build gets a warning through the caller's logger, a run written by this
 * build gets none.
 */
@Tag("unit")
class BuildRevisionCheckTest {

    private final Logger log = mock(Logger.class);

    @Test
    void runWithoutRecordedRevision_Warns() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder().setSimulationRunId("run-a").build();

        BuildRevisionCheck.warnIfWrittenByAnotherBuild(metadata, log);

        verify(log).warn(anyString(), eq("run-a"), eq(BuildInfo.UNKNOWN), eq(BuildInfo.revision()),
            eq("the run does not name its sources"));
    }

    @Test
    void runWrittenByAnotherRevision_Warns() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("run-b")
            .setBuildRevision("0123abcd")
            .build();

        BuildRevisionCheck.warnIfWrittenByAnotherBuild(metadata, log);

        verify(log).warn(anyString(), eq("run-b"), eq("0123abcd"), eq(BuildInfo.revision()),
            eq("its sources are `git checkout 0123abcd`"));
    }

    @Test
    void runWrittenFromUncommittedChanges_SaysTheSourcesAreGone() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("run-d")
            .setBuildRevision("0123abcd" + BuildInfo.DIRTY_SUFFIX)
            .build();

        BuildRevisionCheck.warnIfWrittenByAnotherBuild(metadata, log);

        verify(log).warn(anyString(), eq("run-d"), eq("0123abcd-dirty"), eq(BuildInfo.revision()),
            eq("those sources carried uncommitted changes and cannot be checked out again"));
    }

    @Test
    void runWrittenByThisBuild_IsSilentWhenTheRevisionIsKnown() {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("run-c")
            .setBuildRevision(BuildInfo.revision())
            .build();

        BuildRevisionCheck.warnIfWrittenByAnotherBuild(metadata, log);

        if (BuildInfo.UNKNOWN.equals(BuildInfo.revision())) {
            verify(log).warn(anyString(), eq("run-c"), eq(BuildInfo.UNKNOWN), eq(BuildInfo.UNKNOWN), anyString());
        } else {
            verify(log, never()).warn(anyString(), any(Object[].class));
        }
    }
}
