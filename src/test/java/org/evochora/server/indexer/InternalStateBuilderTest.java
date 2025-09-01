package org.evochora.server.indexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.indexer.ArtifactValidator.ArtifactValidity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class InternalStateBuilderTest {

    private InternalStateBuilder builder;
    private RawOrganismState mockOrganism;
    private ProgramArtifact mockArtifact;

    @BeforeEach
    void setUp() {
        builder = new InternalStateBuilder();
        
        // Create mock organism with some data
        List<Object> drs = new ArrayList<>();
        drs.add(0x00010064); // DATA:100 (type 1, value 100)
        drs.add(0x000100C8); // DATA:200 (type 1, value 200)
        
        List<Object> prs = new ArrayList<>();
        prs.add(0x0000012C); // CODE:300 (type 0, value 300)
        
        List<Object> fprs = new ArrayList<>();
        fprs.add(0x00030190); // STRUCTURE:400 (type 3, value 400)
        
        List<Object> lrs = new ArrayList<>();
        lrs.add(new int[]{1, 2}); // Vector [1, 2]
        
        List<int[]> dps = new ArrayList<>();
        dps.add(new int[]{5, 6}); // Vector [5, 6]
        
        Deque<Object> dataStack = new LinkedList<>();
        dataStack.add(0x000101F4); // DATA:500 (type 1, value 500)
        
        Deque<int[]> locationStack = new LinkedList<>();
        locationStack.add(new int[]{7, 8}); // Vector [7, 8]
        
        mockOrganism = new RawOrganismState(
            1, // id
            null, // parentId
            0L, // birthTick
            "test_program", // programId
            new int[]{0, 0}, // initialPosition
            new int[]{0, 0}, // ip
            new int[]{0, 0}, // dv
            dps, // dps
            0, // activeDpIndex
            100, // er
            drs, // drs
            prs, // prs
            fprs, // fprs
            lrs, // lrs
            dataStack, // dataStack
            locationStack, // locationStack
            new LinkedList<>(), // callStack (empty)
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
    }

    @Test
    void testBuildInternalStateWithValidData() {
        PreparedTickState.InternalState state = builder.buildInternalState(mockOrganism, mockArtifact, ArtifactValidity.VALID);
        
        assertNotNull(state);
        
        // Check data registers
        assertEquals(2, state.dataRegisters().size());
        assertEquals("DR0", state.dataRegisters().get(0).id());
        assertEquals("DATA:100", state.dataRegisters().get(0).value());
        assertEquals("DR1", state.dataRegisters().get(1).id());
        assertEquals("DATA:200", state.dataRegisters().get(1).value());
        
        // Check procedure registers
        assertEquals(1, state.procRegisters().size());
        assertEquals("PR0", state.procRegisters().get(0).id());
        assertEquals("CODE:300", state.procRegisters().get(0).value());
        
        // Check floating point registers
        assertEquals(1, state.fpRegisters().size());
        assertEquals("FPR0", state.fpRegisters().get(0).id());
        assertEquals("STRUCTURE:400", state.fpRegisters().get(0).value());
        
        // Check location registers
        assertEquals(1, state.locationRegisters().size());
        assertEquals("LR0", state.locationRegisters().get(0).id());
        assertEquals("[1|2]", state.locationRegisters().get(0).value());
        
        // Check data stack
        assertEquals(1, state.dataStack().size());
        assertEquals("DATA:500", state.dataStack().get(0));
        
        // Check location stack
        assertEquals(1, state.locationStack().size());
        assertEquals("[7|8]", state.locationStack().get(0));
        
        // Check call stack (empty)
        assertEquals(0, state.callStack().size());
        
        // Check dps
        assertEquals(1, state.dps().size());
        assertEquals(List.of(5, 6), state.dps().get(0));
    }

    @Test
    void testBuildInternalStateWithEmptyRegisters() {
        // Create organism with empty registers
        RawOrganismState emptyOrganism = new RawOrganismState(
            2, // id
            null, // parentId
            0L, // birthTick
            "test_program", // programId
            new int[]{0, 0}, // initialPosition
            new int[]{0, 0}, // ip
            new int[]{0, 0}, // dv
            new ArrayList<>(), // dps
            0, // activeDpIndex
            100, // er
            new ArrayList<>(), // drs (empty)
            new ArrayList<>(), // prs (empty)
            new ArrayList<>(), // fprs (empty)
            new ArrayList<>(), // lrs (empty)
            new LinkedList<>(), // dataStack (empty)
            new LinkedList<>(), // locationStack (empty)
            new LinkedList<>(), // callStack (empty)
            false, // isDead
            false, // instructionFailed
            null, // failureReason
            false, // skipIpAdvance
            new int[]{0, 0}, // ipBeforeFetch
            new int[]{0, 0} // dvBeforeFetch
        );
        
        PreparedTickState.InternalState state = builder.buildInternalState(emptyOrganism, mockArtifact, ArtifactValidity.VALID);
        
        assertNotNull(state);
        assertEquals(0, state.dataRegisters().size());
        assertEquals(0, state.procRegisters().size());
        assertEquals(0, state.fpRegisters().size());
        assertEquals(0, state.locationRegisters().size());
        assertEquals(0, state.dataStack().size());
        assertEquals(0, state.locationStack().size());
        assertEquals(0, state.callStack().size());
        assertEquals(0, state.dps().size());
    }

    @Test
    void testBuildInternalStateWithNullValues() {
        // Create organism with some null values
        List<Object> drsWithNull = new ArrayList<>();
        drsWithNull.add(null);
        drsWithNull.add(0x00010064); // DATA:100 (type 1, value 100)
        
        RawOrganismState organismWithNull = new RawOrganismState(
            3, // id
            null, // parentId
            0L, // birthTick
            "test_program", // programId
            new int[]{0, 0}, // initialPosition
            new int[]{0, 0}, // ip
            new int[]{0, 0}, // dv
            new ArrayList<>(), // dps
            0, // activeDpIndex
            100, // er
            drsWithNull, // drs with null
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
        
        PreparedTickState.InternalState state = builder.buildInternalState(organismWithNull, mockArtifact, ArtifactValidity.VALID);
        
        assertNotNull(state);
        assertEquals(2, state.dataRegisters().size());
        assertEquals("", state.dataRegisters().get(0).value()); // null becomes empty string
        assertEquals("DATA:100", state.dataRegisters().get(1).value());
    }

    @Test
    void testBuildInternalStateWithDifferentValidity() {
        // Test that validity doesn't affect internal state building
        PreparedTickState.InternalState stateValid = builder.buildInternalState(mockOrganism, mockArtifact, ArtifactValidity.VALID);
        PreparedTickState.InternalState stateInvalid = builder.buildInternalState(mockOrganism, mockArtifact, ArtifactValidity.INVALID);
        PreparedTickState.InternalState stateNone = builder.buildInternalState(mockOrganism, mockArtifact, ArtifactValidity.NONE);
        
        assertNotNull(stateValid);
        assertNotNull(stateInvalid);
        assertNotNull(stateNone);
        
        // All should have the same structure
        assertEquals(stateValid.dataRegisters().size(), stateInvalid.dataRegisters().size());
        assertEquals(stateValid.dataRegisters().size(), stateNone.dataRegisters().size());
    }
}
