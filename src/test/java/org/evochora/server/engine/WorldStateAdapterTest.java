package org.evochora.server.engine;

import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.server.contracts.OrganismState;
import org.evochora.server.contracts.WorldStateMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorldStateAdapterTest {

	private Simulation simulation;

	@BeforeAll
	static void init() {
		Instruction.init();
	}

	@BeforeEach
	void setUp() {
		Environment environment = new Environment(new int[]{10,10}, true);
		simulation = new Simulation(environment, false); // debug mode
	}

	@Test
	void resolvesFprBindingsAcrossFramesToDrOrPrForCallStackAndFormalParameters() {
		// Arrange organism
		Organism org = Organism.create(simulation, new int[]{0,0}, Config.INITIAL_ORGANISM_ENERGY, simulation.getLogger());
		org.setProgramId("p1");
		// DR0 = DATA:3, DR1 = DATA:6
		org.setDr(0, new Molecule(Config.TYPE_DATA, 3).toInt());
		org.setDr(1, new Molecule(Config.TYPE_DATA, 6).toInt());

		// Build call stack: older frame = MY_PROC, top frame = MY_PROC2
		Map<Integer, Integer> olderBindings = new HashMap<>();
		olderBindings.put(Instruction.FPR_BASE + 0, 0 + 0); // REG1 -> %DR0
		olderBindings.put(Instruction.FPR_BASE + 1, 1 + 0); // REG2 -> %DR1
		Organism.ProcFrame older = new Organism.ProcFrame(
			"MY_PROC",
			new int[]{0,0},
			new Object[Config.NUM_PROC_REGISTERS],
			new Object[Config.NUM_FORMAL_PARAM_REGISTERS],
			olderBindings
		);

		Map<Integer, Integer> topBindings = new HashMap<>();
		topBindings.put(Instruction.FPR_BASE + 0, 0 + 0); // REG1 -> %DR0
		topBindings.put(Instruction.FPR_BASE + 1, 1 + 0); // REG2 -> %DR1
		Organism.ProcFrame top = new Organism.ProcFrame(
			"MY_PROC2",
			new int[]{0,0},
			new Object[Config.NUM_PROC_REGISTERS],
			new Object[Config.NUM_FORMAL_PARAM_REGISTERS],
			topBindings
		);

		org.getCallStack().clear();
		org.getCallStack().push(older);
		org.getCallStack().push(top);

		// Program artifact with parameter names and typed empty maps
		Map<String, List<String>> procParams = new HashMap<>();
		procParams.put("MY_PROC", List.of("REG1", "REG2"));
		procParams.put("MY_PROC2", List.of("REG1", "REG2"));
		Map<String, List<String>> sources = new HashMap<>();
		Map<int[], Integer> machineCodeLayout = new HashMap<>();
		Map<int[], PlacedMolecule> initialWorldObjects = new HashMap<>();
		Map<Integer, SourceInfo> sourceMap = new HashMap<>();
		Map<Integer, int[]> callSiteBindings = new HashMap<>();
		Map<String, Integer> relativeCoordToLinearAddress = new HashMap<>();
		Map<Integer, int[]> linearAddressToCoord = new HashMap<>();
		Map<Integer, String> labelAddressToName = new HashMap<>();
		Map<String, Integer> registerAliasMap = new HashMap<>();
		ProgramArtifact artifact = new ProgramArtifact(
			"p1",
			sources,
			machineCodeLayout,
			initialWorldObjects,
			sourceMap,
			callSiteBindings,
			relativeCoordToLinearAddress,
			linearAddressToCoord,
			labelAddressToName,
			registerAliasMap,
			procParams
		);

		simulation.setProgramArtifacts(Map.of("p1", artifact));
		simulation.addOrganism(org);

		// Act
		WorldStateMessage msg = WorldStateAdapter.fromSimulation(simulation);
		assertThat(msg.organismStates()).hasSize(1);
		OrganismState state = msg.organismStates().get(0);

		// Assert call stack resolution (top frame first)
		assertThat(state.callStack()).hasSize(2);
		assertThat(state.callStack().get(0)).isEqualTo("MY_PROC2 REG1[%DR0=DATA:3] REG2[%DR1=DATA:6]");
		assertThat(state.callStack().get(1)).isEqualTo("MY_PROC REG1[%DR0=DATA:3] REG2[%DR1=DATA:6]");

		// Assert formal parameters reflect DR bindings for the top frame
		assertThat(state.formalParameters()).containsExactly(
			"REG1[%DR0]=DATA:3",
			"REG2[%DR1]=DATA:6"
		);
	}
}


