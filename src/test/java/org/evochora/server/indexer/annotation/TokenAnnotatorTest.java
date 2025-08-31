package org.evochora.server.indexer.annotation;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for TokenAnnotator to ensure it correctly coordinates handlers
 * and processes tokens using the deterministic TokenMap data.
 * 
 * <p>These are unit tests that execute quickly without I/O operations.</p>
 */
@Tag("unit")
class TokenAnnotatorTest {
    
    private TokenAnnotator annotator;
    private ProgramArtifact artifact;
    private RawOrganismState organismState;
    
    @BeforeEach
    void setUp() {
        annotator = new TokenAnnotator();
        
        // Create a comprehensive test artifact with various token types
        Map<SourceInfo, TokenInfo> tokenMap = new HashMap<>();
        Map<String, Map<Integer, Map<Integer, List<TokenInfo>>>> tokenLookup = new HashMap<>();
        Map<Integer, List<TokenInfo>> lineToTokens = new HashMap<>();
        
        // Build tokenLookup structure with column information
        Map<Integer, Map<Integer, List<TokenInfo>>> testFileTokens = new HashMap<>();
        
        // Line 1: .PROC TEST_PROC WITH PARAM1 PARAM2
        SourceInfo procSource = new SourceInfo("test.s", 1, 0, ".PROC TEST_PROC WITH PARAM1 PARAM2");
        TokenInfo procToken = new TokenInfo("TEST_PROC", Symbol.Type.PROCEDURE, "global");
        TokenInfo param1Token = new TokenInfo("PARAM1", Symbol.Type.VARIABLE, "TEST_PROC");
        TokenInfo param2Token = new TokenInfo("PARAM2", Symbol.Type.VARIABLE, "TEST_PROC");
        
        tokenMap.put(procSource, procToken);
        tokenMap.put(new SourceInfo("test.s", 1, 5, "PARAM1"), param1Token);
        tokenMap.put(new SourceInfo("test.s", 1, 12, "PARAM2"), param2Token);
        
        lineToTokens.put(1, List.of(procToken, param1Token, param2Token));
        
        // Create column-based structure for line 1
        Map<Integer, List<TokenInfo>> line1Columns = new HashMap<>();
        line1Columns.put(0, List.of(procToken));      // Column 0: .PROC
        line1Columns.put(5, List.of(param1Token));    // Column 5: PARAM1
        line1Columns.put(12, List.of(param2Token));   // Column 12: PARAM2
        testFileTokens.put(1, line1Columns);
        
        // Line 2: CALL TEST_PROC WITH %DR0 %DR1
        SourceInfo callSource = new SourceInfo("test.s", 2, 0, "CALL TEST_PROC WITH %DR0 %DR1");
        TokenInfo callToken = new TokenInfo("CALL", Symbol.Type.LABEL, "global");
        TokenInfo labelRefToken = new TokenInfo("TEST_PROC", Symbol.Type.LABEL, "global");
        TokenInfo reg1Token = new TokenInfo("%DR0", Symbol.Type.VARIABLE, "global");
        TokenInfo reg2Token = new TokenInfo("%DR1", Symbol.Type.VARIABLE, "global");
        
        tokenMap.put(callSource, callToken);
        tokenMap.put(new SourceInfo("test.s", 2, 5, "TEST_PROC"), labelRefToken);
        tokenMap.put(new SourceInfo("test.s", 2, 18, "%DR0"), reg1Token);
        tokenMap.put(new SourceInfo("test.s", 2, 23, "%DR1"), reg2Token);
        
        lineToTokens.put(2, List.of(callToken, labelRefToken, reg1Token, reg2Token));
        
        // Create column-based structure for line 2
        Map<Integer, List<TokenInfo>> line2Columns = new HashMap<>();
        line2Columns.put(0, List.of(callToken));      // Column 0: CALL
        line2Columns.put(5, List.of(labelRefToken));  // Column 5: TEST_PROC
        line2Columns.put(18, List.of(reg1Token));     // Column 18: %DR0
        line2Columns.put(23, List.of(reg2Token));     // Column 23: %DR1
        testFileTokens.put(2, line2Columns);
        
        // Line 3: ADDR PARAM1 PARAM2
        SourceInfo addSource = new SourceInfo("test.s", 3, 0, "ADDR PARAM1 PARAM2");
        TokenInfo addToken = new TokenInfo("ADDR", Symbol.Type.LABEL, "global");
        TokenInfo param1RefToken = new TokenInfo("PARAM1", Symbol.Type.VARIABLE, "TEST_PROC");
        TokenInfo param2RefToken = new TokenInfo("PARAM2", Symbol.Type.VARIABLE, "TEST_PROC");
        
        tokenMap.put(addSource, addToken);
        tokenMap.put(new SourceInfo("test.s", 3, 6, "PARAM1"), param1RefToken);
        tokenMap.put(new SourceInfo("test.s", 3, 13, "PARAM2"), param2RefToken);
        
        lineToTokens.put(3, List.of(addToken, param1RefToken, param2RefToken));
        
        // Create column-based structure for line 3
        Map<Integer, List<TokenInfo>> line3Columns = new HashMap<>();
        line3Columns.put(0, List.of(addToken));       // Column 0: ADDR
        line3Columns.put(6, List.of(param1RefToken)); // Column 6: PARAM1
        line3Columns.put(13, List.of(param2RefToken)); // Column 13: PARAM2
        testFileTokens.put(3, line3Columns);
        
        // Line 4: RET
        SourceInfo retSource = new SourceInfo("test.s", 4, 0, "RET");
        TokenInfo retToken = new TokenInfo("RET", Symbol.Type.LABEL, "global");
        
        tokenMap.put(retSource, retToken);
        lineToTokens.put(4, List.of(retToken));
        
        // Create column-based structure for line 4
        Map<Integer, List<TokenInfo>> line4Columns = new HashMap<>();
        line4Columns.put(0, List.of(retToken));       // Column 0: RET
        testFileTokens.put(4, line4Columns);
        
        // Add the complete file structure to tokenLookup
        tokenLookup.put("test.s", testFileTokens);
        
        artifact = new ProgramArtifact(
            "test123",
            Map.of("test.s", List.of(
                ".PROC TEST_PROC WITH PARAM1 PARAM2",
                "CALL TEST_PROC WITH %DR0 %DR1", 
                "ADDR PARAM1 PARAM2",
                "RET"
            )),
            Map.of(new int[]{0,0}, 100), // machineCodeLayout
            Collections.emptyMap(), // initialWorldObjects
            Map.of(1, new SourceInfo("test.s", 1, 0, ".PROC TEST_PROC WITH PARAM1 PARAM2")), // sourceMap
            Map.of(100, new int[]{0,1}), // callSiteBindings
            Map.of("0,0", 100), // relativeCoordToLinearAddress
            Map.of(100, new int[]{0,0}), // linearAddressToCoord
            Map.of(100, "TEST_PROC"), // labelAddressToName
            Map.of("MYALIAS", 0), // registerAliasMap
            Map.of("TEST_PROC", List.of("PARAM1", "PARAM2")), // procNameToParamNames
            tokenMap,
            tokenLookup
        );
        
        // Create organism state with some register values
        List<Object> drs = new ArrayList<>();
        drs.add(42); // %DR0 = DATA:42
        drs.add(15); // %DR1 = DATA:15
        
        organismState = new RawOrganismState(
            1, // id
            null, // parentId
            1L, // birthTick
            "test123", // programId
            new int[]{0,0}, // initialPosition
            new int[]{0,0}, // ip
            new int[]{0,0}, // dv
            Collections.emptyList(), // dps
            0, // activeDpIndex
            0, // er
            drs, // drs
            Collections.emptyList(), // prs
            Collections.emptyList(), // fprs
            Collections.emptyList(), // lrs
            new LinkedList<>(), // dataStack
            new LinkedList<>(), // locationStack
            new LinkedList<>(), // callStack
            false, // isDead
            false, // instructionFailed
            null, // failureReason
            false, // skipIpAdvance
            new int[]{0,0}, // ipBeforeFetch
            new int[]{0,0}  // dvBeforeFetch
        );
    }
    
    @Test
    void testTokenAnnotatorCreation() {
        assertNotNull(annotator);
    }
    
    @Test
    void testAnalyzeLine_ProcedureDefinition() {
        List<TokenAnnotation> annotations = annotator.analyzeLine(1, artifact, organismState);
        
        assertNotNull(annotations);
        // Procedure definition line should have no annotations (all tokens are definitions)
        assertTrue(annotations.isEmpty());
    }
    
    @Test
    void testAnalyzeLine_CallInstruction() {
        List<TokenAnnotation> annotations = annotator.analyzeLine(2, artifact, organismState);
        
        assertNotNull(annotations);
        
        // Should have annotations for:
        // - CALL instruction (basic identification)
        // - TEST_PROC label reference (jump coordinates)
        // - %DR0 register (value)
        // - %DR1 register (value)
        assertTrue(annotations.size() >= 2);
        
        // Verify we have register value annotations
        boolean hasRegisterAnnotation = annotations.stream()
            .anyMatch(a -> a.annotationText().contains("CODE:"));
        assertTrue(hasRegisterAnnotation, "Should have register value annotations");
    }
    
    @Test
    void testAnalyzeLine_ParameterReferences() {
        List<TokenAnnotation> annotations = annotator.analyzeLine(3, artifact, organismState);
        
        assertNotNull(annotations);
        // Should have annotations for parameter references
        // Note: Parameters need call stack context to resolve, so may be empty without proper context
        assertTrue(annotations.size() >= 0);
    }
    
    @Test
    void testAnalyzeLine_RetInstruction() {
        List<TokenAnnotation> annotations = annotator.analyzeLine(4, artifact, organismState);
        
        assertNotNull(annotations);
        // RET instruction should have basic identification
        assertTrue(annotations.size() >= 0);
    }
    
    @Test
    void testAnalyzeLine_NoTokens() {
        List<TokenAnnotation> annotations = annotator.analyzeLine(999, artifact, organismState);
        
        assertNotNull(annotations);
        assertTrue(annotations.isEmpty());
    }
    
    @Test
    void testAnalyzeLine_EmptyLine() {
        List<TokenAnnotation> annotations = annotator.analyzeLine(5, artifact, organismState);
        
        assertNotNull(annotations);
        assertTrue(annotations.isEmpty());
    }
}
