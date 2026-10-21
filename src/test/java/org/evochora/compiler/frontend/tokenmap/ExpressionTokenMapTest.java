package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.TokenKind;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names inside a computation are classified in the token map as the same names standing
 * alone are, at the positions of their own tokens, and a computation with a value is noted with
 * it behind its last token.
 */
@Tag("unit")
class ExpressionTokenMapTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aConstantInsideAComputationHasTheEntryOfTheConstant() throws Exception {
        ProgramArtifact artifact = new Compiler().compile(List.of(
                ".CONST WIDTH 100",
                "SETI %DR0 DATA:(WIDTH-1)",
                ".CONST COPY WIDTH"), "expression_tokens.evo", new EnvironmentProperties(new int[]{100, 100}, true));

        TokenInfo inside = entryAt(artifact.tokenMap(), 2, 17);
        TokenInfo alone = artifact.tokenMap().entrySet().stream()
                .filter(e -> e.getKey().lineNumber() == 3 && "WIDTH".equals(e.getValue().tokenText()))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
        assertThat(inside.tokenText()).isEqualTo("WIDTH");
        assertThat(inside.tokenType()).isEqualTo(TokenKind.CONSTANT);
        assertThat(inside.qualifiedName()).isNotNull().isEqualTo(alone.qualifiedName());
    }

    @Test
    void theCursorHasNoEntry() throws Exception {
        ProgramArtifact artifact = new Compiler().compile(List.of(
                "TARGET: NOP",
                ".CONST HERE (TARGET)",
                ".CONST AT @"), "cursor_tokens.evo", new EnvironmentProperties(new int[]{100, 100}, true));

        assertThat(artifact.tokenMap().values()).noneMatch(info -> "@".equals(info.tokenText()));
        assertThat(entryAt(artifact.tokenMap(), 2, 14).tokenType()).isEqualTo(TokenKind.LABEL);
    }

    @Test
    void aComputationWithAValueIsNotedBehindItsLastToken() throws Exception {
        ProgramArtifact artifact = new Compiler().compile(List.of(
                ".CONST WIDTH 99",
                "SETI %DR0 DATA:(WIDTH+1)"), "expression_note.evo", new EnvironmentProperties(new int[]{100, 100}, true));

        assertThat(artifact.sources().get(0).notes()).containsExactly(new SourceFile.Note(0, 2, 25, "[=100]"));
    }

    private static TokenInfo entryAt(Map<SourceInfo, TokenInfo> tokenMap, int line, int column) {
        return tokenMap.entrySet().stream()
                .filter(e -> e.getKey().lineNumber() == line && e.getKey().columnNumber() == column)
                .map(Map.Entry::getValue).findFirst().orElseThrow();
    }
}
