package org.evochora.datapipeline.utils;

import org.evochora.BuildInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.slf4j.Logger;

/**
 * Warns when a run is read by a build other than the one that wrote it.
 * <p>
 * A run is reproduced exactly only by the build that wrote it: a later build may compute a
 * different trajectory from the same state without any error. Every place that takes a run's
 * metadata to work on the run calls {@link #warnIfWrittenByAnotherBuild} once, so the log of that
 * process says whether its build and the run's build are the same sources. Nothing is refused;
 * reading a run with another build is a decision the reader makes knowingly.
 */
public final class BuildRevisionCheck {

    private BuildRevisionCheck() {
    }

    /**
     * Logs a warning through the given logger when the run's build revision is unknown or differs
     * from this build's, and nothing when both are known and equal.
     *
     * @param metadata the metadata of the run about to be read
     * @param log the logger of the caller, so the warning is attributed to the process that reads
     */
    public static void warnIfWrittenByAnotherBuild(SimulationMetadata metadata, Logger log) {
        String recorded = metadata.getBuildRevision().isEmpty() ? BuildInfo.UNKNOWN : metadata.getBuildRevision();
        if (BuildInfo.matches(recorded)) {
            return;
        }
        log.warn("Run {} was written by build {} and is read by build {}: the trajectory is reproduced "
                + "exactly only by the build that wrote the run; {}",
            metadata.getSimulationRunId(), recorded, BuildInfo.revision(), howToGetTheSources(recorded));
    }

    /**
     * Says how the sources of the recorded revision can be had, or why they cannot.
     *
     * @param recorded the revision a run's metadata carries
     * @return the closing part of the warning
     */
    private static String howToGetTheSources(String recorded) {
        if (BuildInfo.UNKNOWN.equals(recorded)) {
            return "the run does not say which sources that was";
        }
        if (recorded.endsWith(BuildInfo.DIRTY_SUFFIX)) {
            return "those sources carried uncommitted changes and cannot be checked out again";
        }
        return "its sources are `git checkout " + recorded + "`";
    }
}
