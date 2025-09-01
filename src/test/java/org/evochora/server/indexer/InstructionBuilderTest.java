package org.evochora.server.indexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
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
class InstructionBuilderTest {

    private InstructionBuilder builder;
    private RawOrganismState mockOrganism;
    private ProgramArtifact mockArtifact;
    private RawTickState mockTickState;
    private EnvironmentProperties mockEnvProps;

    @BeforeEach
    void setUp() {
        builder = new InstructionBuilder();
        
        // Create mock organism
        mockOrganism = new RawOrganismState(
            1, // id
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
        
        // Create mock artifact
        Map<String, List<String>> sources = new HashMap<>();
        sources.put("test.s", List.of("PROC TEST", "NOP", "RET"));
        
        Map<Integer, org.evochora.compiler.api.SourceInfo> sourceMap = new HashMap<>();
        sourceMap.put(0, new org.evochora.compiler.api.SourceInfo("test.s", 1, 1));
        
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
        
        // Create mock tick state
        mockTickState = new RawTickState(
            1L, // tickNumber
            new ArrayList<>(), // organisms
            new ArrayList<>() // cells
        );
        
        // Create mock environment properties
        mockEnvProps = new EnvironmentProperties(new int[]{10, 10}, true);
    }

    @Test
    void testBuildNextInstructionWithValidData() {
        PreparedTickState.NextInstruction instruction = builder.buildNextInstruction(
            mockOrganism, mockArtifact, ArtifactValidity.VALID, mockTickState, mockEnvProps
        );
        
        assertNotNull(instruction);
        // Note: The actual opcode and arguments depend on the disassembler and organism state
        // We're mainly testing that the method doesn't throw exceptions
    }

    @Test
    void testBuildNextInstructionWithNullEnvProps() {
        // When envProps is null, the method should return a default instruction with error status
        PreparedTickState.NextInstruction instruction = builder.buildNextInstruction(
            mockOrganism, mockArtifact, ArtifactValidity.VALID, mockTickState, null
        );
        
        assertNotNull(instruction);
        assertEquals(0, instruction.opcodeId());
        assertEquals("UNKNOWN", instruction.opcodeName());
        assertEquals("ERROR", instruction.lastExecutionStatus().status());
        assertTrue(instruction.lastExecutionStatus().failureReason().contains("EnvironmentProperties not available"));
    }

    // Note: buildExecutionStatus is private, so we can't test it directly
    // The execution status is tested indirectly through buildNextInstruction
}
