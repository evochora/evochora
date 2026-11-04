package org.evochora.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Checks, before Evochora starts, that the Java it is about to start on is not older than the one
 * Evochora is compiled for, and says so when it is.
 * <p>
 * An older Java cannot load Evochora's classes and would stop with an
 * {@code UnsupportedClassVersionError} that names class file versions rather than Java versions,
 * or, before that, at a launcher option it does not know. The start scripts therefore run this
 * class on its own, in a JVM started without Evochora's options, before they start Evochora: it
 * exits with status 0 when the Java is new enough, and otherwise prints why not and exits with
 * status 1. It is compiled for the oldest Java the compiler still targets, in a source set of its
 * own, and uses nothing a newer Java added. The scripts skip the check for the runtime a
 * distribution brings, which is the one Evochora is built for.
 * <p>
 * The Java version the code needs is the one the build compiles it for. The build records it in
 * {@value #BUILD_INFO} under {@value #REQUIRED_JAVA}; a classpath whose file lacks that entry was
 * not made by the build, and the check refuses it rather than pass without knowing.
 * <p>
 * The refusal goes to {@code System.err}, not through SLF4J: the check runs before Evochora and
 * its logging are started.
 */
public final class JavaVersionGuard {

    /** Classpath location of the file the build writes about itself. */
    static final String BUILD_INFO = "/evochora-build.properties";

    /** The key under which the build records the Java version the code is compiled for. */
    static final String REQUIRED_JAVA = "requiredJava";

    private JavaVersionGuard() {
    }

    /**
     * Checks the running Java and exits with status 0 when Evochora may start on it, or prints
     * why not and exits with status 1.
     *
     * @param args not used
     */
    public static void main(String[] args) {
        String refusal;
        try {
            refusal = refusal(readBuildInfo(), System.getProperty("java.specification.version"),
                    System.getProperty("java.home"));
        } catch (IOException | IllegalArgumentException e) {
            // IllegalArgumentException is what Properties.load throws for a malformed escape
            refusal = "Cannot check the Java version: reading " + BUILD_INFO + " failed: " + e.getMessage();
        }
        if (refusal != null) {
            System.err.println(refusal);
            System.exit(1);
        }
        System.exit(0);
    }

    /**
     * Decides whether Evochora may start on the running Java.
     *
     * @param buildInfo the contents of {@value #BUILD_INFO}, empty when the file is missing
     * @param runningJava the {@code java.specification.version} of the running Java: its feature
     *                    version, or {@code 1.} followed by it for Java 8 and older
     * @param javaHome the {@code java.home} of the running Java, named in the message
     * @return the message to stop with, or {@code null} when the running Java is new enough
     */
    static String refusal(Properties buildInfo, String runningJava, String javaHome) {
        Integer required = featureVersion(buildInfo.getProperty(REQUIRED_JAVA));
        if (required == null) {
            return "Cannot check the Java version: " + BUILD_INFO + " on the classpath carries no readable "
                    + REQUIRED_JAVA + " entry, so this classpath was not made by Evochora's build.";
        }
        Integer running = featureVersion(runningJava != null && runningJava.trim().startsWith("1.")
                ? runningJava.trim().substring(2) : runningJava);
        if (running == null) {
            return "Cannot check the Java version: the running Java reports its version as '" + runningJava
                    + "' (" + javaHome + ").";
        }
        if (running < required) {
            return "Evochora needs Java " + required + " or newer, but was started with Java " + running
                    + " from " + javaHome + ". Set EVOCHORA_JAVA_HOME to a Java " + required + " installation.";
        }
        return null;
    }

    private static Properties readBuildInfo() throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = JavaVersionGuard.class.getResourceAsStream(BUILD_INFO)) {
            if (stream != null) {
                properties.load(stream);
            }
        }
        return properties;
    }

    private static Integer featureVersion(String version) {
        if (version == null) {
            return null;
        }
        try {
            return Integer.valueOf(version.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
