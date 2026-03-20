package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.features.macro.MacroDefinition;
import org.evochora.compiler.features.macro.MacroExpansionHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for dynamic handler registration on {@link PreProcessorContext}.
 */
class PreProcessorContextDynamicHandlerTest {

    private final PreProcessorContext context = new PreProcessorContext();

    @Test
    @Tag("unit")
    void registerAndRetrieve() {
        IPreProcessorHandler handler = createHandler("INC", List.of("R"), List.of(opcode("ADDI")));
        context.registerDynamicHandler("INC", handler);

        Optional<IPreProcessorHandler> result = context.getDynamicHandler("INC");
        assertThat(result).isPresent().containsSame(handler);
    }

    @Test
    @Tag("unit")
    void getDynamicHandler_unregistered_returnsEmpty() {
        Optional<IPreProcessorHandler> result = context.getDynamicHandler("NONEXISTENT");
        assertThat(result).isEmpty();
    }

    @Test
    @Tag("unit")
    void caseInsensitive() {
        IPreProcessorHandler handler = createHandler("FOO", List.of(), List.of(opcode("NOP")));
        context.registerDynamicHandler("FOO", handler);

        assertThat(context.getDynamicHandler("foo")).isPresent().containsSame(handler);
        assertThat(context.getDynamicHandler("Foo")).isPresent().containsSame(handler);
    }

    @Test
    @Tag("unit")
    void idempotentReRegistration() {
        Token nameToken = identifier("INC");
        List<Token> params = List.of(identifier("R"));
        List<Token> body = List.of(opcode("ADDI"));

        IPreProcessorHandler handler1 = new MacroExpansionHandler(new MacroDefinition(nameToken, params, body));
        IPreProcessorHandler handler2 = new MacroExpansionHandler(new MacroDefinition(nameToken, params, body));

        context.registerDynamicHandler("INC", handler1);
        context.registerDynamicHandler("INC", handler2);

        assertThat(context.getDynamicHandler("INC")).isPresent().containsSame(handler1);
    }

    @Test
    @Tag("unit")
    void conflictingReRegistration_throws() {
        IPreProcessorHandler handler1 = createHandler("FOO", List.of(), List.of(opcode("NOP")));
        IPreProcessorHandler handler2 = createHandler("FOO", List.of(), List.of(opcode("SETI")));

        context.registerDynamicHandler("FOO", handler1);

        assertThatThrownBy(() -> context.registerDynamicHandler("FOO", handler2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOO");
    }

    private static IPreProcessorHandler createHandler(String name, List<String> paramNames, List<Token> body) {
        Token nameToken = identifier(name);
        List<Token> params = paramNames.stream().map(PreProcessorContextDynamicHandlerTest::identifier).toList();
        return new MacroExpansionHandler(new MacroDefinition(nameToken, params, body));
    }

    private static Token identifier(String text) {
        return new Token(TokenType.IDENTIFIER, text, null, 1, 1, "test");
    }

    private static Token opcode(String text) {
        return new Token(TokenType.OPCODE, text, null, 1, 1, "test");
    }
}
