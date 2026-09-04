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
 * Unit tests for {@link PreProcessorHandlerRegistry}: the lookup by name, and what happens
 * when a name is registered a second time, as a macro definition that is included twice does.
 */
@Tag("unit")
class PreProcessorHandlerRegistryTest {

    private final PreProcessorHandlerRegistry registry = new PreProcessorContext().handlers();

    @Test
    void registerAndRetrieve() {
        IPreProcessorHandler handler = createHandler("INC", List.of("R"), List.of(opcode("ADDI")));
        registry.register("INC", handler);

        Optional<IPreProcessorHandler> result = registry.get("INC");
        assertThat(result).isPresent().containsSame(handler);
    }

    @Test
    void unregisteredNameReturnsEmpty() {
        assertThat(registry.get("NONEXISTENT")).isEmpty();
    }

    @Test
    void lookupIsCaseInsensitive() {
        IPreProcessorHandler handler = createHandler("FOO", List.of(), List.of(opcode("NOP")));
        registry.register("FOO", handler);

        assertThat(registry.get("foo")).isPresent().containsSame(handler);
        assertThat(registry.get("Foo")).isPresent().containsSame(handler);
    }

    @Test
    void registeringTheSameDefinitionAgainIsIgnored() {
        Token nameToken = identifier("INC");
        List<Token> params = List.of(identifier("R"));
        List<Token> body = List.of(opcode("ADDI"));

        IPreProcessorHandler handler1 = new MacroExpansionHandler(new MacroDefinition(nameToken, params, body));
        IPreProcessorHandler handler2 = new MacroExpansionHandler(new MacroDefinition(nameToken, params, body));

        registry.register("INC", handler1);
        registry.register("INC", handler2);

        assertThat(registry.get("INC")).isPresent().containsSame(handler1);
    }

    @Test
    void registeringADifferentHandlerUnderAHeldNameThrows() {
        // Two macros of the same name from two places are two definitions
        IPreProcessorHandler handler1 = createHandler("FOO", List.of(), List.of(opcode("NOP")));
        IPreProcessorHandler handler2 = new MacroExpansionHandler(
                new MacroDefinition(identifierAt("FOO", 7), List.of(), List.of(opcode("SETI"))));

        registry.register("FOO", handler1);

        assertThatThrownBy(() -> registry.register("FOO", handler2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOO");
    }

    private static IPreProcessorHandler createHandler(String name, List<String> paramNames, List<Token> body) {
        Token nameToken = identifier(name);
        List<Token> params = paramNames.stream().map(PreProcessorHandlerRegistryTest::identifier).toList();
        return new MacroExpansionHandler(new MacroDefinition(nameToken, params, body));
    }

    private static Token identifier(String text) {
        return identifierAt(text, 1);
    }

    private static Token identifierAt(String text, int line) {
        return new Token(TokenType.IDENTIFIER, text, null, line, 1, "test");
    }

    private static Token opcode(String text) {
        return new Token(TokenType.OPCODE, text, null, 1, 1, "test");
    }

    @Test
    void aDefinitionOfAModuleIsGoneWhenTheModuleIsLeft() {
        IPreProcessorHandler outer = (pp, ctx) -> { };
        IPreProcessorHandler inner = (pp, ctx) -> { };
        registry.defineInModule("INC", outer);

        registry.enterModule();
        assertThat(registry.get("INC")).isEmpty();
        registry.defineInModule("INC", inner);
        assertThat(registry.get("INC")).contains(inner);
        registry.leaveModule();

        assertThat(registry.get("INC")).contains(outer);
    }

    @Test
    void sharedHandlersAnswerInEveryModule() {
        IPreProcessorHandler source = (pp, ctx) -> { };
        registry.register(".SOURCE", source);

        registry.enterModule();

        assertThat(registry.get(".SOURCE")).contains(source);
    }
}
