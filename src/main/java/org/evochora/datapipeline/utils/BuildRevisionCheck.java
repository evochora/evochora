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
     * from this build's, and nothing when both are known and equal. Each build is named by its
     * version where it has one and by its revision otherwise; what the warning advises follows
     * the build that wrote the run.
     *
     * @param metadata the metadata of the run about to be read
     * @param log the logger of the caller, so the warning is attributed to the process that reads
     */
    public static void warnIfWrittenByAnotherBuild(SimulationMetadata metadata, Logger log) {
        String recorded = metadata.getBuildRevision().isEmpty() ? BuildInfo.UNKNOWN : metadata.getBuildRevision();
        if (BuildInfo.matches(recorded)) {
            return;
        }
        String recordedVersion = metadata.getBuildVersion();
        log.warn("Run {} was written by {} and is read by {}: only the build that wrote a run reproduces "
                + "it exactly; {}",
            metadata.getSimulationRunId(),
            BuildInfo.describe(recordedVersion, recorded),
            BuildInfo.describe(BuildInfo.version().orElse(""), BuildInfo.revision()),
            recordedVersion.isBlank()
                ? howToGetTheSources(recorded)
                : "use Evochora " + recordedVersion + " to reproduce or continue it");
    }

    /**
     * Says how the sources of the recorded revision can be had, or why they cannot. This is the
     * advice for a run written by a build without a version, which exists only as its sources.
     *
     * @param recorded the revision a run's metadata carries
     * @return the closing part of the warning
     */
    private static String howToGetTheSources(String recorded) {
        if (BuildInfo.UNKNOWN.equals(recorded)) {
            return "the run does not name its sources";
        }
        // Sources shipped from a tree with uncommitted changes are as little to be had from the
        // repository as that tree
        String shipped = recorded.endsWith(BuildInfo.REBUILT_SUFFIX)
            ? recorded.substring(0, recorded.length() - BuildInfo.REBUILT_SUFFIX.length()) : recorded;
        if (shipped.endsWith(BuildInfo.DIRTY_SUFFIX)) {
            return "those sources carried uncommitted changes and cannot be checked out again";
        }
        if (recorded.endsWith(BuildInfo.REBUILT_SUFFIX)) {
            return "its sources were shipped as those of `git checkout " + shipped + "` and may have been changed "
                + "before the build";
        }
        return "its sources are `git checkout " + recorded + "`";
    }
}
