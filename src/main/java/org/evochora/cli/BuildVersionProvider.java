package org.evochora.cli;

import org.evochora.BuildInfo;

import picocli.CommandLine.IVersionProvider;

/**
 * Answers {@code --version} on every command with what the build recorded about itself: its
 * version, if it was made for a release, the git revision of its sources, and the DOI of that
 * release where it has one.
 */
final class BuildVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return BuildInfo.versionLines();
    }
}
