package org.evochora.compiler.frontend.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ParserStatementRegistry}.
 */
@Tag("unit")
class ParserStatementRegistryTest {

    private ParserStatementRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ParserStatementRegistry();
    }

    @Test
    void register_succeeds() {
        IParserStatementHandler handler = ctx -> null;
        registry.register(".TEST", handler);

        assertThat(registry.get(".TEST")).contains(handler);
    }

    @Test
    void register_caseInsensitive() {
        IParserStatementHandler handler = ctx -> null;
        registry.register(".org", handler);

        assertThat(registry.get(".ORG")).contains(handler);
        assertThat(registry.get(".Org")).contains(handler);
    }

    @Test
    void register_duplicateThrows() {
        registry.register(".TEST", ctx -> null);

        assertThatThrownBy(() -> registry.register(".TEST", ctx -> null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void register_duplicateCaseInsensitive() {
        registry.register(".ORG", ctx -> null);

        assertThatThrownBy(() -> registry.register(".org", ctx -> null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void get_unregisteredReturnsEmpty() {
        assertThat(registry.get("UNKNOWN")).isEmpty();
    }

    @Test
    void registerDefault_succeeds() {
        IParserStatementHandler handler = ctx -> null;
        registry.registerDefault(handler);

        assertThat(registry.getDefault()).contains(handler);
    }

    @Test
    void registerDefault_doubleThrows() {
        registry.registerDefault(ctx -> null);

        assertThatThrownBy(() -> registry.registerDefault(ctx -> null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getDefault_withoutRegistration() {
        assertThat(registry.getDefault()).isEmpty();
    }
}
