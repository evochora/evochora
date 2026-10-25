package org.evochora.cli;

import org.evochora.BuildInfo;

import picocli.CommandLine.IVersionProvider;

/**
 * Answers {@code --version} on every command with what the build recorded about itself: the
 * version it was made as and the git revision of its sources.
 */
final class BuildVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] {"Evochora " + BuildInfo.version() + " (" + BuildInfo.revision() + ")"};
    }
}
