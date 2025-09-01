package org.evochora.server.indexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.indexer.ArtifactValidator.ArtifactValidity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class SourceViewBuilderTest {

    private SourceViewBuilder builder;
    private RawOrganismState mockOrganism;
    private ProgramArtifact mockArtifact;

    @BeforeEach
    void setUp() {
        builder = new SourceViewBuilder();
        
        // Create mock organism with IP at position [0, 0] using correct constructor
        mockOrganism = new RawOrganismState(
            1, // id (int, not long)
            null, // parentId
            0L, // birthTick
            "test_program", // programId
            new int[]{0, 0}, // initialPosition
            new int[]{0, 0}, // ip
            new int[]{0, 0}, // dv
            new ArrayList<>(), // dps
            0, // activeDpIndex
            100, // er
            new ArrayList<>(), // drs
            new ArrayList<>(), // prs
            new ArrayList<>(), // fprs
            new ArrayList<>(), // lrs
            new LinkedList<>(), // dataStack
            new LinkedList<>(), // locationStack
            new LinkedList<>(), // callStack
            false, // isDead
            false, // instructionFailed
            null, // failureReason
            false, // skipIpAdvance
            new int[]{0, 0}, // ipBeforeFetch
            new int[]{0, 0} // dvBeforeFetch
        );
        
        // Create mock artifact with source mapping using correct constructor
        Map<String, List<String>> sources = new HashMap<>();
        sources.put("test.s", List.of("PROC TEST", "NOP", "RET"));
        
        Map<Integer, SourceInfo> sourceMap = new HashMap<>();
        sourceMap.put(0, new SourceInfo("test.s", 1, 1));
        
        Map<String, Integer> coordToLinear = new HashMap<>();
        coordToLinear.put("0|0", 0);
        
        Map<String, List<String>> procParams = new HashMap<>();
        procParams.put("TEST", List.of("param1", "param2"));
        
        mockArtifact = new ProgramArtifact(
            "test_program", // programId
            sources, // sources
            new HashMap<>(), // machineCodeLayout
            new HashMap<>(), // initialWorldObjects
            sourceMap, // sourceMap
            new HashMap<>(), // callSiteBindings
            coordToLinear, // relativeCoordToLinearAddress
            new HashMap<>(), // linearAddressToCoord
            new HashMap<>(), // labelAddressToName
            new HashMap<>(), // registerAliasMap
            procParams, // procNameToParamNames
            new HashMap<>(), // tokenMap
            new HashMap<>() // tokenLookup
        );
    }

    @Test
    void testBuildSourceViewWithValidArtifact() {
        PreparedTickState.SourceView view = builder.buildSourceView(mockOrganism, mockArtifact, ArtifactValidity.VALID);
        
        assertNotNull(view);
        assertEquals("test.s", view.fileName());
        assertEquals(1, view.currentLine());
        assertEquals(3, view.lines().size());
        assertTrue(view.lines().get(0).isCurrent());
    }

    @Test
    void testBuildSourceViewWithInvalidArtifact() {
        PreparedTickState.SourceView view = builder.buildSourceView(mockOrganism, mockArtifact, ArtifactValidity.INVALID);
        
        assertNull(view);
    }

    @Test
    void testBuildSourceViewWithNullArtifact() {
        PreparedTickState.SourceView view = builder.buildSourceView(mockOrganism, null, ArtifactValidity.NONE);
        
        assertNull(view);
    }

    @Test
    void testBuildSourceViewWithNoSourceCode() {
        // Create artifact without source code
        ProgramArtifact noSourceArtifact = new ProgramArtifact(
            "test_program", // programId
            new HashMap<>(), // sources (empty)
            new HashMap<>(), // machineCodeLayout
            new HashMap<>(), // initialWorldObjects
            new HashMap<>(), // sourceMap (empty)
            new HashMap<>(), // callSiteBindings
            new HashMap<>(), // relativeCoordToLinearAddress (empty)
            new HashMap<>(), // linearAddressToCoord
            new HashMap<>(), // labelAddressToName
            new HashMap<>(), // registerAliasMap
            new HashMap<>(), // procNameToParamNames
            new HashMap<>(), // tokenMap
            new HashMap<>() // tokenLookup
        );
        
        PreparedTickState.SourceView view = builder.buildSourceView(mockOrganism, noSourceArtifact, ArtifactValidity.VALID);
        
        assertNotNull(view);
        assertEquals(0, view.lines().size());
    }

    @Test
    void testBuildSourceViewWithDifferentIPPosition() {
        // Create organism with IP at different position
        RawOrganismState differentIPOrganism = new RawOrganismState(
            1, // id (int, not long)
            null, // parentId
            0L, // birthTick
            "test_program", // programId
            new int[]{0, 0}, // initialPosition
            new int[]{5, 5}, // ip (different position)
            new int[]{0, 0}, // dv
            new ArrayList<>(), // dps
            0, // activeDpIndex
            100, // er
            new ArrayList<>(), // drs
            new ArrayList<>(), // prs
            new ArrayList<>(), // fprs
            new ArrayList<>(), // lrs
            new LinkedList<>(), // dataStack
            new LinkedList<>(), // locationStack
            new LinkedList<>(), // callStack
            false, // isDead
            false, // instructionFailed
            null, // failureReason
            false, // skipIpAdvance
            new int[]{0, 0}, // ipBeforeFetch
            new int[]{0, 0} // dvBeforeFetch
        );
        
        // Create artifact without mapping for position [5, 5]
        Map<String, List<String>> sources = new HashMap<>();
        sources.put("test.s", List.of("PROC TEST", "NOP", "RET"));
        
        Map<Integer, SourceInfo> sourceMap = new HashMap<>();
        sourceMap.put(0, new SourceInfo("test.s", 1, 1));
        // Note: No mapping for position [5, 5]
        
        Map<String, Integer> coordToLinear = new HashMap<>();
        coordToLinear.put("0|0", 0);
        // Note: No mapping for "5|5"
        
        Map<String, List<String>> procParams = new HashMap<>();
        procParams.put("TEST", List.of("param1", "param2"));
        
        ProgramArtifact differentIPArtifact = new ProgramArtifact(
            "test_program", // programId
            sources, // sources
            new HashMap<>(), // machineCodeLayout
            new HashMap<>(), // initialWorldObjects
            sourceMap, // sourceMap
            new HashMap<>(), // callSiteBindings
            coordToLinear, // relativeCoordToLinearAddress
            new HashMap<>(), // linearAddressToCoord
            new HashMap<>(), // labelAddressToName
            new HashMap<>(), // registerAliasMap
            procParams, // procNameToParamNames
            new HashMap<>(), // tokenMap
            new HashMap<>() // tokenLookup
        );
        
        PreparedTickState.SourceView view = builder.buildSourceView(differentIPOrganism, differentIPArtifact, ArtifactValidity.VALID);
        
        assertNotNull(view);
        // When IP position is not in sourceMap, SourceViewBuilder returns fallback value 1
        assertEquals(1, view.currentLine()); // Fallback value, not null
    }

    @Test
    void testSourceLineGeneration() {
        PreparedTickState.SourceView view = builder.buildSourceView(mockOrganism, mockArtifact, ArtifactValidity.VALID);
        
        assertNotNull(view);
        List<PreparedTickState.SourceLine> lines = view.lines();
        
        assertEquals(3, lines.size());
        assertEquals(1, lines.get(0).number());
        assertEquals("PROC TEST", lines.get(0).content());
        assertTrue(lines.get(0).isCurrent());
        
        assertEquals(2, lines.get(1).number());
        assertEquals("NOP", lines.get(1).content());
        assertFalse(lines.get(1).isCurrent());
        
        assertEquals(3, lines.get(2).number());
        assertEquals("RET", lines.get(2).content());
        assertFalse(lines.get(2).isCurrent());
    }
}
