package org.evochora.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.util.Properties;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * When the entry point lets Evochora start, what it says when it does not, and that the build
 * gives it what it checks against.
 */
@Tag("unit")
class JavaVersionGuardTest {

    private static final String JAVA_HOME = "/opt/some-java";

    @Test
    void refusal_RequiredOrNewerJava_Starts() {
        assertThat(JavaVersionGuard.refusal(requiring(" 25 "), "25", JAVA_HOME)).isNull();
        assertThat(JavaVersionGuard.refusal(requiring("25"), "26", JAVA_HOME)).isNull();
    }

    @Test
    void refusal_OlderJava_NamesTheRequiredAndTheRunningJava() {
        String refusal = JavaVersionGuard.refusal(requiring("25"), "21", JAVA_HOME);

        assertThat(refusal).contains("needs Java 25 or newer", "started with Java 21", JAVA_HOME,
                "Set EVOCHORA_JAVA_HOME");
    }

    @Test
    void refusal_MissingOrUnreadableEntry_Refuses() {
        assertThat(JavaVersionGuard.refusal(new Properties(), "25", JAVA_HOME))
                .contains(JavaVersionGuard.REQUIRED_JAVA);
        assertThat(JavaVersionGuard.refusal(requiring(" "), "25", JAVA_HOME))
                .contains(JavaVersionGuard.REQUIRED_JAVA);
        assertThat(JavaVersionGuard.refusal(requiring("twenty-five"), "25", JAVA_HOME))
                .contains(JavaVersionGuard.REQUIRED_JAVA);
    }

    @Test
    void refusal_UnreadableRunningVersion_Refuses() {
        assertThat(JavaVersionGuard.refusal(requiring("25"), "1.8", JAVA_HOME)).contains("'1.8'", JAVA_HOME);
        assertThat(JavaVersionGuard.refusal(requiring("25"), null, JAVA_HOME)).isNotNull();
    }

    /**
     * The tests run on the Java of the toolchain, so the Java the build records as required is the
     * one running them.
     */
    @Test
    void buildInfo_RecordsTheJavaTheCodeIsCompiledFor() throws IOException {
        Properties buildInfo = new Properties();
        try (InputStream stream = JavaVersionGuard.class.getResourceAsStream(JavaVersionGuard.BUILD_INFO)) {
            assertThat(stream).as("%s on the test classpath", JavaVersionGuard.BUILD_INFO).isNotNull();
            buildInfo.load(stream);
        }

        assertThat(buildInfo.getProperty(JavaVersionGuard.REQUIRED_JAVA))
                .isEqualTo(String.valueOf(Runtime.version().feature()));
    }

    @Test
    void entryPoint_IsTheMainMethodOfTheCommandLineInterface() throws ReflectiveOperationException {
        assertThat(JavaVersionGuard.entryPoint().type())
                .isEqualTo(MethodType.methodType(void.class, String[].class));
    }

    private static Properties requiring(String requiredJava) {
        Properties properties = new Properties();
        properties.setProperty(JavaVersionGuard.REQUIRED_JAVA, requiredJava);
        return properties;
    }
}
