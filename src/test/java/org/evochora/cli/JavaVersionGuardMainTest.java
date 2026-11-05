package org.evochora.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@link JavaVersionGuard} the way the start scripts do, as a JVM of its own, and checks the
 * exit status and the message the scripts act on.
 * <p>
 * Integration test: it starts a process. The build info the guard reads comes first on that
 * process's classpath, so each test decides what the guard finds.
 */
@Tag("integration")
class JavaVersionGuardMainTest {

    /** What the guard's JVM ended with. */
    private record Outcome(int exitStatus, String stderr) {
    }

    private static Outcome runGuard(Path buildInfoDir) throws IOException, InterruptedException, URISyntaxException {
        String guardClasses = Path.of(JavaVersionGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
        // Written to a file rather than read from the pipe, so that the wait below bounds the test
        // even if the guard never ends
        Path stderrFile = buildInfoDir.resolve("guard-stderr.txt");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", buildInfoDir + File.pathSeparator + guardClasses,
                JavaVersionGuard.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(stderrFile.toFile())
                .start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("The guard did not end within 10 seconds");
        }
        return new Outcome(process.exitValue(), Files.readString(stderrFile, StandardCharsets.UTF_8).trim());
    }

    private static Path buildInfo(Path dir, String content) throws IOException {
        Files.writeString(dir.resolve(JavaVersionGuard.BUILD_INFO.substring(1)), content, StandardCharsets.ISO_8859_1);
        return dir;
    }

    @Test
    void lets_TheRunningJava_StartWhenItIsTheRequiredOne(@TempDir Path dir) throws Exception {
        String running = System.getProperty("java.specification.version");

        Outcome outcome = runGuard(buildInfo(dir, JavaVersionGuard.REQUIRED_JAVA + "=" + running + "\n"));

        assertThat(outcome.exitStatus()).isZero();
        assertThat(outcome.stderr()).isEmpty();
    }

    @Test
    void refuses_AnOlderJava_WithStatusOneAndTheMessage(@TempDir Path dir) throws Exception {
        Outcome outcome = runGuard(buildInfo(dir, JavaVersionGuard.REQUIRED_JAVA + "=999\n"));

        assertThat(outcome.exitStatus()).isEqualTo(1);
        assertThat(outcome.stderr()).startsWith("Evochora needs Java 999 or newer");
    }

    @Test
    void refuses_AnUnreadableBuildInfo_WithStatusOneAndTheReason(@TempDir Path dir) throws Exception {
        // A malformed escape is what makes Properties.load fail
        Outcome outcome = runGuard(buildInfo(dir, JavaVersionGuard.REQUIRED_JAVA + "=\\uZZZZ\n"));

        assertThat(outcome.exitStatus()).isEqualTo(1);
        assertThat(outcome.stderr()).startsWith("Cannot check the Java version: reading " + JavaVersionGuard.BUILD_INFO + " failed: ");
    }
}
