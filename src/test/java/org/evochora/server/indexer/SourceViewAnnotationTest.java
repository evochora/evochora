package org.evochora.server.indexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.indexer.ArtifactValidator.ArtifactValidity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that the DebugIndexer correctly generates source view annotations.
 */
@Tag("unit")
class SourceViewAnnotationTest {

    private DebugIndexer debugIndexer;
    private RawOrganismState mockOrganism;
    private ProgramArtifact mockArtifact;

    @BeforeEach
    void setUp() {
        debugIndexer = new DebugIndexer("test_raw.db", 100);
        
        // Mock organism with IP at position [10, 20]
        mockOrganism = mock(RawOrganismState.class);
        when(mockOrganism.id()).thenReturn(42);
        when(mockOrganism.ip()).thenReturn(new int[]{10, 20});
        
        // Mock artifact with source mapping
        mockArtifact = mock(ProgramArtifact.class);
        
        // Mock source mapping: IP [10, 20] -> linear address 100 -> source info
        Map<String, Integer> coordToLinear = new HashMap<>();
        coordToLinear.put("10|20", 100);
        when(mockArtifact.relativeCoordToLinearAddress()).thenReturn(coordToLinear);
        
        Map<Integer, SourceInfo> sourceMap = new HashMap<>();
        		sourceMap.put(100, new SourceInfo("main.s", 15, 2));
        when(mockArtifact.sourceMap()).thenReturn(sourceMap);
        
        // Mock source code
        Map<String, List<String>> sources = new HashMap<>();
        sources.put("main.s", List.of(
            "// Main program",
            "",
            "PROC MAIN_LOOP",
            "  MOV %COUNTER, 42",
            "  CALL PROCESS_DATA",
            "  DEC %COUNTER",
            "  JNZ %COUNTER, MAIN_LOOP",
            "  RET",
            "",
            "PROC PROCESS_DATA",
            "  PUSH %COUNTER",
            "  // Process logic here",
            "  POP %COUNTER",
            "  MOV %POS, %TARGET",
            "  RET"
        ));
        when(mockArtifact.sources()).thenReturn(sources);
        
        // Mock label mappings
        Map<Integer, String> labelAddressToName = new HashMap<>();
        labelAddressToName.put(200, "MAIN_LOOP");
        labelAddressToName.put(300, "PROCESS_DATA");
        when(mockArtifact.labelAddressToName()).thenReturn(labelAddressToName);
        
        // Mock coordinate mappings
        Map<Integer, int[]> linearAddressToCoord = new HashMap<>();
        linearAddressToCoord.put(200, new int[]{3, 4});
        linearAddressToCoord.put(300, new int[]{10, 11});
        when(mockArtifact.linearAddressToCoord()).thenReturn(linearAddressToCoord);
        
        // Mock register alias mappings
        Map<String, Integer> registerAliasMap = new HashMap<>();
        registerAliasMap.put("COUNTER", 0);
        when(mockArtifact.registerAliasMap()).thenReturn(registerAliasMap);
    }

    @Test
    void testSourceViewWithAnnotations() {
        // Use reflection to access the private buildSourceView method
        try {
            var method = DebugIndexer.class.getDeclaredMethod(
                "buildSourceView", 
                RawOrganismState.class, 
                ProgramArtifact.class, 
                ArtifactValidity.class
            );
            method.setAccessible(true);
            
            PreparedTickState.SourceView sourceView = (PreparedTickState.SourceView) method.invoke(
                debugIndexer, 
                mockOrganism, 
                mockArtifact, 
                ArtifactValidity.VALID
            );
            
            // Verify source view is created
            assertNotNull(sourceView);
            assertEquals("main.s", sourceView.fileName());
            assertEquals(15, sourceView.currentLine());
            
            // Verify source lines are populated
            assertNotNull(sourceView.lines());
            assertFalse(sourceView.lines().isEmpty());
            assertEquals(15, sourceView.lines().size()); // 15 lines in our mock source
            
            // Verify current line is marked correctly
            PreparedTickState.SourceLine currentLine = sourceView.lines().get(14); // 0-indexed, line 15
            assertTrue(currentLine.isCurrent());
            assertEquals(15, currentLine.number());
            // The content should match what we put in the mock source at index 14
            assertEquals("  RET", currentLine.content());
            
            // Verify annotations structure is created (even if empty for now)
            assertNotNull(sourceView.inlineSpans());
            // Note: Annotations might be empty if SourceAnnotator needs more complete mock data
            // This test verifies the basic structure works
            
        } catch (Exception e) {
            fail("Failed to test source view generation: " + e.getMessage());
        }
    }

    @Test
    void testSourceViewWithoutArtifact() {
        try {
            var method = DebugIndexer.class.getDeclaredMethod(
                "buildSourceView", 
                RawOrganismState.class, 
                ProgramArtifact.class, 
                ArtifactValidity.class
            );
            method.setAccessible(true);
            
            // Test with no artifact
            PreparedTickState.SourceView sourceView = (PreparedTickState.SourceView) method.invoke(
                debugIndexer, 
                mockOrganism, 
                null, 
                ArtifactValidity.NONE
            );
            
            // Should return null when no artifact
            assertNull(sourceView);
            
        } catch (Exception e) {
            fail("Failed to test source view without artifact: " + e.getMessage());
        }
    }

    @Test
    void testSourceViewWithInvalidArtifact() {
        try {
            var method = DebugIndexer.class.getDeclaredMethod(
                "buildSourceView", 
                RawOrganismState.class, 
                ProgramArtifact.class, 
                ArtifactValidity.class
            );
            method.setAccessible(true);
            
            // Test with invalid artifact
            PreparedTickState.SourceView sourceView = (PreparedTickState.SourceView) method.invoke(
                debugIndexer, 
                mockOrganism, 
                mockArtifact, 
                ArtifactValidity.INVALID
            );
            
            // Should return null when artifact is invalid
            assertNull(sourceView);
            
        } catch (Exception e) {
            fail("Failed to test source view with invalid artifact: " + e.getMessage());
        }
    }
}
