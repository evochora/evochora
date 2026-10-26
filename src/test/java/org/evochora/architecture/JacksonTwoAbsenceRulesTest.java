package org.evochora.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Holds Jackson 2 off the classpath, which keeps a Javalin created past
 * {@link org.evochora.node.processes.http.JavalinFactory} from writing wrong JSON without notice.
 * <p>
 * Javalin writes JSON through Jackson 2 unless told otherwise, and Jackson 2 ignores the Jackson 3
 * annotations of the node's response types, among them the one that sends 64-bit identifiers as
 * strings. Without Jackson 2 on the classpath, such a Javalin fails on its first JSON response.
 * Nothing else would report the day an update brings Jackson 2 back and that failure turns into
 * identifiers the browser rounds.
 * <p>
 * This rule fails as soon as Jackson 2 can be loaded. Whoever sees it fail finds out which
 * dependency brings it, and either keeps it off the classpath or guards the creation of every
 * Javalin by other means.
 */
@Tag("unit")
class JacksonTwoAbsenceRulesTest {

    private static final String JACKSON_TWO_MAPPER = "com.fasterxml.jackson.databind.ObjectMapper";

    @Test
    void jacksonTwoIsNotOnTheClasspath() {
        assertThatThrownBy(() -> Class.forName(JACKSON_TWO_MAPPER, false, getClass().getClassLoader()))
                .as("Jackson 2 is on the classpath: a Javalin created past JavalinFactory would write "
                        + "JSON without the node's Jackson 3 annotations")
                .isInstanceOf(ClassNotFoundException.class);
    }
}
