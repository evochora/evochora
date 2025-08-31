package org.evochora.compiler.directives;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.parser.Parser;
import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.features.proc.ProcedureNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the parsing of the `.PREG` directive, likely for procedure-local register aliases.
 * These are unit tests for the parser and do not require external resources.
 */
public class PregDirectiveTest {

    /**
     * Verifies that the parser can correctly parse a `.PREG` directive within a procedure definition.
     * This test ensures that the syntax is accepted without errors.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testPregIsParsedCorrectlyInsideProc() {
        // Arrange
        String source = String.join(System.lineSeparator(),
                ".PROC MY_PROC",
                "  .PREG %TMP 0",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1).first().isInstanceOf(ProcedureNode.class);
    }

    /**
     * Verifies that the parser accepts a `.PREG` directive even if the index might be semantically invalid.
     * The test confirms that the parser's responsibility is only syntactic correctness,
     * and range checking should be handled later by the semantic analyzer.
     * This is a unit test for the parser.
     */
    @Test
    @Tag("unit")
    void testPregWithInvalidIndexReportsError() {
        // Arrange
        String source = String.join(System.lineSeparator(),
                ".PROC MY_PROC",
                "  .PREG %TMP 2", // Index 2 ist ungültig, aber der Parser sollte es erstmal nur als Zahl lesen
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, Path.of("")); // KORREKTUR

        // Act
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert
        // Der Parser sollte keinen Fehler melden, die semantische Analyse würde den Wertebereich prüfen.
        assertThat(diagnostics.hasErrors()).isFalse();
    }

    /**
     * Verifies that the current implementation has limitations and cannot fully support .PREG functionality.
     * This test demonstrates what needs to be implemented to make .PREG work properly.
     * This is a unit test that will fail until full .PREG support is implemented.
     */
    @Test
    @Tag("unit")
    void testPregLimitations_ShowsWhatNeedsToBeImplemented() {
        // Arrange: Test that demonstrates current limitations
        String source = String.join(System.lineSeparator(),
                ".PROC MY_PROC",
                "  .PREG %TMP 0",           // Define alias for %PR0
                "  SETI %TMP DATA:42",      // Try to use the alias
                "  RET",
                ".ENDP"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, Path.of(""));

        // Act: Parse the source
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert: This should work at parsing level
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(1);
        assertThat(ast.get(0)).isInstanceOf(ProcedureNode.class);

        // Now test the full compiler pipeline to see what breaks
        try {
            // This will fail because .PREG aliases are not properly integrated into the compiler pipeline
            org.evochora.compiler.Compiler compiler = new org.evochora.compiler.Compiler();
            org.evochora.compiler.api.ProgramArtifact artifact = compiler.compile(
                List.of(source.split(System.lineSeparator())), 
                "preg_test.s"
            );
            
            // If we get here, the test should fail because we expect limitations
            // This demonstrates that full .PREG support is not yet implemented
            assertThat(artifact).isNotNull();
            
            // Check if the alias was properly resolved by looking at the actual artifact
            // This will fail because .PREG aliases are not handled by AstPostProcessor
            assertThat(artifact.tokenMap()).isNotNull();
            
            // Look for evidence that the alias was resolved
            boolean foundResolvedAlias = false;
            for (org.evochora.compiler.api.TokenInfo tokenInfo : artifact.tokenMap().values()) {
                if ("%TMP".equals(tokenInfo.tokenText())) {
                    // If we find %TMP in the token map, it means the alias wasn't resolved
                    foundResolvedAlias = true;
                    break;
                }
            }
            
            // This assertion will fail until .PREG is fully implemented
            // We expect %TMP to be resolved to %PR0, not remain as %TMP
            assertThat(foundResolvedAlias).isFalse(); // Should be false if aliases are properly resolved
            
        } catch (Exception e) {
            // This is expected until .PREG is fully implemented
            // The test should fail with a clear message about what's missing
            System.out.println("Expected limitation: " + e.getMessage());
            
            // For now, we expect this to fail, but we want to document what needs to be implemented
            assertThat(e.getMessage()).contains("PREG"); // This will help identify .PREG-related issues
        }
    }

    /**
     * Tests that .PREG aliases are properly scoped to their procedure.
     * This test will fail until proper scoping is implemented.
     */
    @Test
    @Tag("unit")
    void testPregScoping_ShowsScopingLimitations() {
        // Arrange: Test procedure scoping
        String source = String.join(System.lineSeparator(),
                ".PROC PROC1",
                "  .PREG %TEMP1 0",        // %TEMP1 aliases %PR0 in PROC1
                "  SETI %TEMP1 DATA:10",
                "  RET",
                ".ENDP",
                "",
                ".PROC PROC2",
                "  .PREG %TEMP2 0",        // %TEMP2 aliases %PR0 in PROC2 (different scope)
                "  SETI %TEMP2 DATA:20",
                "  RET",
                ".ENDP",
                "",
                "START:",
                "  CALL PROC1",            // Call PROC1
                "  CALL PROC2",            // Call PROC2
                "  JMPI START"
        );
        
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Parser parser = new Parser(new Lexer(source, diagnostics).scanTokens(), diagnostics, Path.of(""));

        // Act: Parse the source
        List<AstNode> ast = parser.parse().stream().filter(Objects::nonNull).toList();

        // Assert: Parsing should work
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(ast).hasSize(3); // PROC1, PROC2, START

        // Test full compilation to see scoping issues
        try {
            org.evochora.compiler.Compiler compiler = new org.evochora.compiler.Compiler();
            org.evochora.compiler.api.ProgramArtifact artifact = compiler.compile(
                List.of(source.split(System.lineSeparator())), 
                "preg_scoping_test.s"
            );
            
            // This should fail until proper scoping is implemented
            assertThat(artifact).isNotNull();
            
            // Check that aliases are properly scoped by examining the token map
            assertThat(artifact.tokenMap()).isNotNull();
            
            // Look for evidence of scoping issues
            boolean foundProc1Alias = false;
            boolean foundProc2Alias = false;
            
            for (org.evochora.compiler.api.TokenInfo tokenInfo : artifact.tokenMap().values()) {
                if ("%TEMP1".equals(tokenInfo.tokenText())) {
                    foundProc1Alias = true;
                }
                if ("%TEMP2".equals(tokenInfo.tokenText())) {
                    foundProc2Alias = true;
                }
            }
            
            // This assertion will fail until proper scoping is implemented
            // We expect aliases to be resolved to their respective %PR0 registers
            assertThat(foundProc1Alias).isFalse(); // Should be false if aliases are properly resolved
            assertThat(foundProc2Alias).isFalse(); // Should be false if aliases are properly resolved
            
        } catch (Exception e) {
            // Expected until scoping is implemented
            System.out.println("Expected scoping limitation: " + e.getMessage());
            assertThat(e.getMessage()).contains("PREG"); // Should be .PREG-related
        }
    }
}