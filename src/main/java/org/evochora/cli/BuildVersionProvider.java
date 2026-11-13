package org.evochora.cli;

import org.evochora.BuildInfo;

import picocli.CommandLine.IVersionProvider;

/**
 * Answers {@code --version} on every command with what the build recorded about itself: its
 * version, if it was made for a release, and the git revision of its sources.
 */
final class BuildVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        return new String[] {BuildInfo.version()
            .map(version -> "Evochora " + version + " (" + BuildInfo.revision() + ")")
            .orElse("Evochora (revision " + BuildInfo.revision() + ")")};
    }
}
