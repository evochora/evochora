package org.evochora.compiler.frontend.preprocessor;

import java.util.Map;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.features.macro.MacroDefinition;
import org.evochora.compiler.features.macro.MacroExpansionHandler;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.frontend.BlockKind;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PreProcessorHandlerRegistry}: the lookup by name, and what happens
 * when a name is registered a second time, as a macro definition that is included twice does.
 */
@Tag("unit")
class PreProcessorHandlerRegistryTest {

    private final PreProcessorHandlerRegistry registry = new PreProcessorContext("", Map.of(), "<memory>", CompilerOptions.defaults()).handlers();

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
    void registeringTheSameHandlerAgainIsIgnored() {
        IPreProcessorHandler handler = createHandler("INC", List.of("R"), List.of(opcode("ADDI")));

        registry.register("INC", handler);
        registry.register("INC", handler);

        assertThat(registry.get("INC")).isPresent().containsSame(handler);
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
        return new Token(TokenType.IDENTIFIER, text, null, new SourceInfo("test", line, 1, "", 0));
    }

    private static Token opcode(String text) {
        return new Token(TokenType.OPCODE, text, null, new SourceInfo("test", 1, 1, "", 0));
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

    private static final IPreProcessorBlockHandler BLOCK = (preProcessor, context, block) -> { };

    @Test
    void aBlockKindAnswersForEveryOneOfItsWordsInAnyCase() {
        registry.registerBlock(new BlockKind(Set.of(".IFX"), ".ENDX", Set.of(".ELSEX")), BLOCK, false);

        assertThat(registry.isBlockWord(".ifx")).isTrue();
        assertThat(registry.isBlockWord(".ELSEX")).isTrue();
        assertThat(registry.isBlockWord(".EndX")).isTrue();
        assertThat(registry.isBlockWord(".OTHER")).isFalse();
        assertThat(registry.blockKindOf(".elsex")).map(BlockKind::closer).contains(".ENDX");
        assertThat(registry.blockHandlerOf(".ifx")).containsSame(BLOCK);
        assertThat(registry.blockHandlerOf(".ENDX")).isEmpty();
        assertThat(registry.blockHandlerOf(".ELSEX")).isEmpty();
        assertThat(registry.isStored(".ELSEX")).isFalse();
    }

    @Test
    void aStoredKindIsStoredUnderEveryOneOfItsWords() {
        registry.registerBlock(new BlockKind(Set.of(".LOOP"), ".ENDLOOP", Set.of()), BLOCK, true);

        assertThat(registry.isStored(".loop")).isTrue();
        assertThat(registry.isStored(".ENDLOOP")).isTrue();
        assertThat(registry.isStored(".OTHER")).isFalse();
    }

    @Test
    void registeringAnEqualBlockKindAgainIsIgnored() {
        registry.registerBlock(new BlockKind(Set.of(".IFX"), ".ENDX", Set.of()), BLOCK, false);

        registry.registerBlock(new BlockKind(Set.of(".ifx"), ".endx", Set.of()), BLOCK, false);

        assertThat(registry.blockKindOf(".IFX")).contains(new BlockKind(Set.of(".IFX"), ".ENDX", Set.of()));
    }

    @Test
    void aBlockKindRegisteredAgainWithAnotherHandlerIsRejected() {
        registry.registerBlock(new BlockKind(Set.of(".IFX"), ".ENDX", Set.of()), BLOCK, false);

        IPreProcessorBlockHandler other = (preProcessor, context, block) -> { };
        assertThatThrownBy(() -> registry.registerBlock(new BlockKind(Set.of(".IFX"), ".ENDX", Set.of()), other, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".IFX");
    }

    @Test
    void aBlockKindThatClaimsAWordOfAnotherKindIsRejected() {
        registry.registerBlock(new BlockKind(Set.of(".IFX"), ".ENDX", Set.of(".ELSEX")), BLOCK, false);

        assertThatThrownBy(() -> registry.registerBlock(new BlockKind(Set.of(".LOOP"), ".ENDLOOP", Set.of(".ELSEX")), BLOCK, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".ELSEX")
                .hasMessageContaining(".ENDX");
        assertThat(registry.isBlockWord(".LOOP")).isFalse();
    }

    @Test
    void aBlockWordTakesNoHandlerOfItsOwnAndAHeldNameBecomesNoBlockWord() {
        IPreProcessorHandler handler = (preProcessor, context) -> { };
        registry.registerBlock(new BlockKind(Set.of(".IFX"), ".ENDX", Set.of(".ELSEX")), BLOCK, false);
        registry.register(".OTHER", handler);

        assertThatThrownBy(() -> registry.register(".ENDX", handler))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".ENDX");
        assertThatThrownBy(() -> registry.registerBlock(new BlockKind(Set.of(".OTHER"), ".ENDOTHER", Set.of()), BLOCK, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(".OTHER");
        assertThat(registry.isBlockWord(".OTHER")).isFalse();
    }

    @Test
    void aTopLevelOnlyDirectiveIsRecognisedInAnyCase() {
        registry.registerTopLevelOnly(".Source");

        assertThat(registry.isTopLevelOnly(".SOURCE")).isTrue();
        assertThat(registry.isTopLevelOnly(".source")).isTrue();
        assertThat(registry.isTopLevelOnly(".IMPORT")).isFalse();
    }
}
