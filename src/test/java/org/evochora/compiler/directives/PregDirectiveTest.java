package org.evochora.compiler.directives;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ParserStatementRegistry;
import org.evochora.compiler.features.proc.PregDirectiveHandler;
import org.evochora.compiler.features.proc.ProcDirectiveHandler;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.features.proc.ProcedureNode;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
public class PregDirectiveTest {

    private final EnvironmentProperties testEnvProps = new EnvironmentProperties(new int[]{100, 100}, true);

    @Test
    @Tag("unit")
    void testPregIsParsedCorrectlyInsideProc() {
        String source = String.join(System.lineSeparator(),
                ".PROC MY_PROC",
                "  .PREG %TMP %PDR0",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, registry());

        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(ProcedureNode.class);
    }

    @Test
    @Tag("unit")
    void testPregWithInvalidIndexReportsError() {
        String source = String.join(System.lineSeparator(),
                ".PROC MY_PROC",
                "  .PREG %TMP %PDR99",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, registry());

        parser.parse();

        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("Procedure register '%PDR99' is out of bounds. Valid range: %PDR0-%PDR7");
    }

    @Test
    @Tag("unit")
    void testPregFunctionality_ShowsFullImplementation() {
        String source = String.join(System.lineSeparator(),
                ".PROC MY_PROC",
                "  .PREG %TMP %PDR0",
                "  SETI %TMP DATA:42",
                "  RET",
                ".ENDP"
        );
        org.evochora.runtime.isa.Instruction.init();
        
        try {
            org.evochora.compiler.Compiler compiler = new org.evochora.compiler.Compiler();
            
            try {
                ProgramArtifact artifact = compiler.compile(
                List.of(source.split(System.lineSeparator())), 
                "preg_test.s", testEnvProps
            );
            
            assertThat(artifact).isNotNull();
            assertThat(artifact.tokenMap()).isNotNull();
            
            boolean foundAliasInTokenMap = false;
            for (org.evochora.compiler.api.TokenInfo tokenInfo : artifact.tokenMap().values()) {
                if ("%TMP".equals(tokenInfo.tokenText()) && tokenInfo.tokenType() == org.evochora.compiler.api.TokenKind.ALIAS) {
                    foundAliasInTokenMap = true;
                    break;
                }
            }
            
            assertThat(foundAliasInTokenMap).isTrue();
            assertThat(artifact.programId()).isNotNull();
            
            } catch (org.evochora.compiler.api.CompilationException e) {
                throw new AssertionError("Compilation failed: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            throw new AssertionError("Unexpected exception: " + e.getMessage(), e);
        }
    }

    @Test
    @Tag("unit")
    void testPregScoping_ShowsScopingLimitations() {
        String source = String.join(System.lineSeparator(),
                ".PROC PROC1",
                "  .PREG %TEMP1 %PDR0",
                "  SETI %TEMP1 DATA:10",
                "  RET",
                ".ENDP",
                "",
                ".PROC PROC2",
                "  .PREG %TEMP2 %PDR0",
                "  SETI %TEMP2 DATA:20",
                "  RET",
                ".ENDP",
                "",
                "START:",
                "  CALL PROC1",
                "  CALL PROC2",
                "  JMPI START"
        );
        
        try {
            org.evochora.compiler.Compiler compiler = new org.evochora.compiler.Compiler();
            ProgramArtifact artifact = compiler.compile(
                List.of(source.split(System.lineSeparator())), 
                "preg_scoping_test.s", testEnvProps
            );
            
            assertThat(artifact).isNotNull();
            assertThat(artifact.tokenMap()).isNotNull();
            
            boolean foundProc1Alias = false;
            boolean foundProc2Alias = false;
            
            for (org.evochora.compiler.api.TokenInfo tokenInfo : artifact.tokenMap().values()) {
                if ("%TEMP1".equals(tokenInfo.tokenText()) && tokenInfo.tokenType() == org.evochora.compiler.api.TokenKind.ALIAS) {
                    foundProc1Alias = true;
                }
                if ("%TEMP2".equals(tokenInfo.tokenText()) && tokenInfo.tokenType() == org.evochora.compiler.api.TokenKind.ALIAS) {
                    foundProc2Alias = true;
                }
            }
            
            assertThat(foundProc1Alias).isTrue();
            assertThat(foundProc2Alias).isTrue();
            
        } catch (Exception e) {
            throw new AssertionError("Compilation failed: " + e.getMessage(), e);
        }
    }

    private static ParserStatementRegistry registry() {
        ParserStatementRegistry reg = new ParserStatementRegistry();
        reg.register(".PROC", new ProcDirectiveHandler());
        reg.register(".PREG", new PregDirectiveHandler());
        reg.register(".LABEL", new org.evochora.compiler.features.label.LabelDirectiveHandler());
        reg.register("CALL", new org.evochora.compiler.features.proc.CallStatementHandler());
        reg.registerDefault(new org.evochora.compiler.features.instruction.InstructionParsingHandler());
        return reg;
    }
}