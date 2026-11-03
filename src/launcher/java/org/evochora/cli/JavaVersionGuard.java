package org.evochora.cli;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Properties;

/**
 * The entry point of the distribution: refuses to start on a Java older than the one Evochora is
 * compiled for, with a message that says so, and otherwise hands over to
 * {@code CommandLineInterface}.
 * <p>
 * An older Java cannot load Evochora's classes and would stop with an
 * {@code UnsupportedClassVersionError} that names class file versions rather than Java versions.
 * This class is therefore compiled for an older Java than the rest of the code, in a source set of
 * its own, and refers to {@code CommandLineInterface} only by name: nothing compiled for the newer
 * Java is loaded before the check has passed.
 * <p>
 * The Java version the code needs is the one the build compiles it for. The build records it in
 * {@value #BUILD_INFO} under {@value #REQUIRED_JAVA}; a classpath whose file lacks that entry was
 * not made by the build, and the guard refuses it rather than start without the check.
 */
public final class JavaVersionGuard {

    /** Classpath location of the file the build writes about itself. */
    static final String BUILD_INFO = "/evochora-build.properties";

    /** The key under which the build records the Java version the code is compiled for. */
    static final String REQUIRED_JAVA = "requiredJava";

    private static final String ENTRY_POINT = "org.evochora.cli.CommandLineInterface";

    private JavaVersionGuard() {
    }

    /**
     * Checks the running Java and starts the command line interface, or exits with status 1.
     *
     * @param args the command line, passed on unchanged
     * @throws Throwable whatever the command line interface throws, unwrapped
     */
    public static void main(String[] args) throws Throwable {
        String refusal;
        try {
            refusal = refusal(readBuildInfo(), System.getProperty("java.specification.version"),
                    System.getProperty("java.home"));
        } catch (IOException e) {
            refusal = "Cannot check the Java version: reading " + BUILD_INFO + " failed: " + e.getMessage();
        }
        if (refusal != null) {
            System.err.println(refusal);
            System.exit(1);
        }
        entryPoint().invokeExact(args);
    }

    /**
     * Decides whether Evochora may start on the running Java.
     *
     * @param buildInfo the contents of {@value #BUILD_INFO}, empty when the file is missing
     * @param runningJava the {@code java.specification.version} of the running Java
     * @param javaHome the {@code java.home} of the running Java, named in the message
     * @return the message to stop with, or {@code null} when the running Java is new enough
     */
    static String refusal(Properties buildInfo, String runningJava, String javaHome) {
        Integer required = featureVersion(buildInfo.getProperty(REQUIRED_JAVA));
        if (required == null) {
            return "Cannot check the Java version: " + BUILD_INFO + " on the classpath carries no readable "
                    + REQUIRED_JAVA + " entry, so this classpath was not made by Evochora's build.";
        }
        Integer running = featureVersion(runningJava);
        if (running == null) {
            return "Cannot check the Java version: the running Java reports its version as '" + runningJava
                    + "' (" + javaHome + ").";
        }
        if (running < required) {
            return "Evochora needs Java " + required + " or newer, but was started with Java " + running
                    + " from " + javaHome + ". Set JAVA_HOME to a Java " + required + " installation.";
        }
        return null;
    }

    /**
     * Finds the method the guard hands over to.
     *
     * @return {@code CommandLineInterface.main}, typed {@code (String[])void}
     * @throws ReflectiveOperationException when the class or its main method is missing
     */
    static MethodHandle entryPoint() throws ReflectiveOperationException {
        return MethodHandles.publicLookup().findStatic(Class.forName(ENTRY_POINT), "main",
                MethodType.methodType(void.class, String[].class));
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
            return Integer.valueOf(version.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
