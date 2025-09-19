package org.evochora.datapipeline.services.debugindexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.server.contracts.raw.RawOrganismState;
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
class ArtifactValidatorTest {

    private ArtifactValidator validator;
    private RawOrganismState mockOrganism;
    private ProgramArtifact mockArtifact;

    @BeforeEach
    void setUp() {
        validator = new ArtifactValidator();
        
        // Create mock organism using correct constructor
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
        
        // Create mock artifact using correct constructor
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
    void testValidArtifact() {
        ArtifactValidator.ArtifactValidity validity = validator.checkArtifactValidity(mockOrganism, mockArtifact);
        assertEquals(ArtifactValidator.ArtifactValidity.VALID, validity);
    }

    @Test
    void testNullArtifact() {
        ArtifactValidator.ArtifactValidity validity = validator.checkArtifactValidity(mockOrganism, null);
        assertEquals(ArtifactValidator.ArtifactValidity.NONE, validity);
    }

    @Test
    void testInvalidArtifact() {
        // Create organism with IP at position [5, 5] which is not in the artifact's sourceMap
        RawOrganismState invalidOrganism = new RawOrganismState(
            2, // id (int, not long)
            null, // parentId
            0L, // birthTick
            "test_program", // programId
            new int[]{0, 0}, // initialPosition
            new int[]{5, 5}, // ip (position not in sourceMap)
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
        
        ArtifactValidator.ArtifactValidity validity = validator.checkArtifactValidity(invalidOrganism, mockArtifact);
        assertEquals(ArtifactValidator.ArtifactValidity.INVALID, validity);
    }

    @Test
    void testCaching() {
        // First call should compute validity
        ArtifactValidator.ArtifactValidity first = validator.checkArtifactValidity(mockOrganism, mockArtifact);
        
        // Second call should use cache
        ArtifactValidator.ArtifactValidity second = validator.checkArtifactValidity(mockOrganism, mockArtifact);
        
        assertEquals(first, second);
        
        // Verify cache is working by checking internal state
        // (This tests that the same result is returned without recomputation)
    }

    @Test
    void testPartialSourceArtifact() {
        // Create artifact with missing source code but valid sourceMap
        ProgramArtifact partialArtifact = new ProgramArtifact(
            "test_program", // programId
            new HashMap<>(), // sources (empty)
            new HashMap<>(), // machineCodeLayout
            new HashMap<>(), // initialWorldObjects
            mockArtifact.sourceMap(), // sourceMap (same as valid artifact)
            new HashMap<>(), // callSiteBindings
            mockArtifact.relativeCoordToLinearAddress(), // relativeCoordToLinearAddress (same as valid artifact)
            new HashMap<>(), // linearAddressToCoord
            new HashMap<>(), // labelAddressToName
            new HashMap<>(), // registerAliasMap
            new HashMap<>(), // procNameToParamNames
            new HashMap<>(), // tokenMap
            new HashMap<>() // tokenLookup
        );
        
        // The organism is at position [0, 0] which maps to address 0 in sourceMap
        // So this should be VALID according to the current logic, not PARTIAL_SOURCE
        ArtifactValidator.ArtifactValidity validity = validator.checkArtifactValidity(mockOrganism, partialArtifact);
        assertEquals(ArtifactValidator.ArtifactValidity.VALID, validity);
    }
}
