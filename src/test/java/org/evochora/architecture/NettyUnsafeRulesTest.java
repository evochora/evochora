package org.evochora.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.util.Version;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Holds the permission for sun.misc.Unsafe to the Netty that needs it.
 * <p>
 * Netty 4.1, which ActiveMQ Artemis brings along, allocates memory through the memory-access methods
 * of sun.misc.Unsafe, which the JVM warns about and a future release denies. The build therefore
 * starts every JVM with {@code --sun-misc-unsafe-memory-access=allow}. That permission is owed to
 * Netty 4.1 alone, and nothing else would report the day an update of Artemis brings a Netty that
 * no longer needs it.
 * <p>
 * This rule fails as soon as the Netty on the classpath leaves the 4.1 line. Whoever sees it fail
 * checks whether that Netty still uses the memory-access methods of sun.misc.Unsafe: if not, the
 * flag goes from build.gradle.kts and this rule goes with it; if so, the rule names the new line.
 */
@Tag("unit")
class NettyUnsafeRulesTest {

    private static final String LINE_THAT_NEEDS_UNSAFE = "4.1.";

    @Test
    void theNettyOnTheClasspathIsTheLineTheUnsafePermissionIsFor() {
        String version = Version.identify().get("netty-common").artifactVersion();

        assertThat(version)
                .as("Netty %s is on the classpath: check whether it still needs "
                        + "--sun-misc-unsafe-memory-access=allow in build.gradle.kts", version)
                .startsWith(LINE_THAT_NEEDS_UNSAFE);
    }
}
