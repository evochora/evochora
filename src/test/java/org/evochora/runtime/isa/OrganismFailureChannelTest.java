package org.evochora.runtime.isa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.SimulationFault;
import org.evochora.runtime.isa.Instruction.InstructionInfo;
import org.evochora.runtime.isa.Instruction.OperandSource;
import org.evochora.runtime.isa.instructions.NopInstruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.LocationValue;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/**
 * Proves the one property the runtime's fault rule rests on: whatever an organism supplies to an
 * instruction, the instruction never throws. It either does its work or books a failure through
 * {@link Organism#instructionFailed(String)}; an exception would be a defect and end the run as a
 * {@link SimulationFault}.
 * <p>
 * The cases are not written by hand. Every registered instruction is taken from the registry with
 * its operand sources, and every operand slot is filled with each value an organism can put there:
 * a scalar or a vector where the other was meant, a register that holds no position, a register id
 * that names no register or the wrong bank, a data stack that is too short, a vector whose
 * components overflow. The cartesian product over the slots of one instruction is small, because
 * no instruction has more than a few operands. On top of that, each instruction runs once under
 * every organism profile that the operands do not reach: a zero marker register, a full data
 * stack, a location stack that is empty, holds no position, or is full.
 * <p>
 * What comes out of a case is not asserted here beyond the absence of a throw and, for a failed
 * instruction, a reason: which failure an instruction books for which input is specified by the
 * targeted tests of its family. State beyond operands and stacks — the call stack, pointers at the
 * edge of a bounded world — has tests of its own as well.
 */
@Tag("unit")
class OrganismFailureChannelTest {

    private static final int[] START = {5, 5};
    private static final int[] POSITION = {3, 3};
    private static final int[] UNIT_VECTOR = {1, 0};
    private static final int[] ZERO_VECTOR = {0, 0};
    private static final int[] BIG_VECTOR = {7, -7};
    private static final int[] ALL_MIN_VECTOR = {Integer.MIN_VALUE, Integer.MIN_VALUE};
    /** An id inside the register table that no bank covers. */
    private static final int UNUSED_REGISTER_ID = 100;
    /** An id beyond the register table. */
    private static final int OUT_OF_TABLE_REGISTER_ID = 5000;
    private static final int LABEL_HASH = 0x1234;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /** One value an organism can leave in a register, in a stack slot or in an argument cell. */
    private enum Kind {
        SCALAR, VECTOR, ZERO_VECTOR, BIG_VECTOR, ALL_MIN_VECTOR, NO_POSITION, POSITION,
        UNUSED_ID, OUT_OF_TABLE_ID, DATA_REGISTER_ID, MISSING,
        IMMEDIATE_ZERO, IMMEDIATE_SEVEN, IMMEDIATE_NEGATIVE, LABEL
    }

    private static final List<Kind> REGISTER_KINDS = List.of(
            Kind.SCALAR, Kind.VECTOR, Kind.ZERO_VECTOR, Kind.ALL_MIN_VECTOR, Kind.NO_POSITION,
            Kind.UNUSED_ID, Kind.OUT_OF_TABLE_ID);
    private static final List<Kind> LOCATION_REGISTER_KINDS = List.of(
            Kind.POSITION, Kind.NO_POSITION, Kind.DATA_REGISTER_ID, Kind.UNUSED_ID, Kind.OUT_OF_TABLE_ID);
    private static final List<Kind> STACK_KINDS = List.of(
            Kind.SCALAR, Kind.VECTOR, Kind.ZERO_VECTOR, Kind.ALL_MIN_VECTOR, Kind.NO_POSITION, Kind.MISSING);
    private static final List<Kind> IMMEDIATE_KINDS = List.of(
            Kind.IMMEDIATE_ZERO, Kind.IMMEDIATE_SEVEN, Kind.IMMEDIATE_NEGATIVE);
    private static final List<Kind> VECTOR_KINDS = List.of(Kind.VECTOR, Kind.ZERO_VECTOR, Kind.BIG_VECTOR);
    private static final List<Kind> LABEL_KINDS = List.of(Kind.LABEL);

    /** The organism's state apart from what the operands name. */
    private enum Profile { DEFAULT, ZERO_MARKER, DATA_STACK_FULL, LOCATION_STACK_EMPTY, LOCATION_STACK_NO_POSITION, LOCATION_STACK_FULL }

    private static List<Kind> kindsFor(OperandSource source) {
        return switch (source) {
            case REGISTER -> REGISTER_KINDS;
            case LOCATION_REGISTER -> LOCATION_REGISTER_KINDS;
            case STACK -> STACK_KINDS;
            case IMMEDIATE -> IMMEDIATE_KINDS;
            case VECTOR -> VECTOR_KINDS;
            case LABEL -> LABEL_KINDS;
        };
    }

    @TestFactory
    Stream<DynamicTest> noInstructionThrowsForAnyOperandAnOrganismCanSupply() {
        return Instruction.getInstructionSetInfo().stream()
                .sorted(Comparator.comparingInt(InstructionInfo::opcodeId))
                .map(info -> DynamicTest.dynamicTest(info.name(), () -> runEveryCase(info)));
    }

    /**
     * A CODE cell can hold a value that names no instruction - inside the range the registered
     * opcodes span, beyond it, or negative - and the organism runs it like every other failure.
     */
    @TestFactory
    Stream<DynamicTest> noUnknownOpcodeThrows() {
        int highestRegistered = Instruction.getInstructionSetInfo().stream()
                .mapToInt(InstructionInfo::opcodeId).max().orElseThrow();
        int unregisteredInside = java.util.stream.IntStream.range(0, highestRegistered)
                .filter(id -> Instruction.getSignatureById(id).isEmpty()).findFirst().orElseThrow();
        return Stream.of(unregisteredInside, highestRegistered + 1, 8192, -5)
                .map(id -> new InstructionInfo(id, "unknown opcode " + id, NopInstruction.class))
                .map(info -> DynamicTest.dynamicTest(info.name(), () -> runEveryCase(info)));
    }

    private void runEveryCase(InstructionInfo info) {
        List<OperandSource> sources = Instruction.OPERAND_SOURCES.getOrDefault(info.opcodeId(), List.of());
        Environment environment = new Environment(new int[]{32, 32}, true);
        Simulation simulation = SimulationTestUtils.createSimulation(environment);
        int cases = 0;
        try {
            for (List<Kind> kinds : product(sources)) {
                runCase(simulation, environment, info, sources, kinds, Profile.DEFAULT);
                cases++;
            }
            List<Kind> firstKinds = new ArrayList<>();
            for (OperandSource source : sources) {
                firstKinds.add(kindsFor(source).get(0));
            }
            for (Profile profile : Profile.values()) {
                if (profile != Profile.DEFAULT) {
                    runCase(simulation, environment, info, sources, firstKinds, profile);
                    cases++;
                }
            }
        } finally {
            simulation.shutdown();
        }
        assertThat(cases).isGreaterThanOrEqualTo(Profile.values().length);
    }

    /** Every combination of one kind per slot. */
    private static List<List<Kind>> product(List<OperandSource> sources) {
        List<List<Kind>> combinations = new ArrayList<>();
        combinations.add(List.of());
        for (OperandSource source : sources) {
            List<List<Kind>> extended = new ArrayList<>();
            for (List<Kind> prefix : combinations) {
                for (Kind kind : kindsFor(source)) {
                    List<Kind> next = new ArrayList<>(prefix);
                    next.add(kind);
                    extended.add(next);
                }
            }
            combinations = extended;
        }
        return combinations;
    }

    private static void runCase(Simulation simulation, Environment environment, InstructionInfo info,
                                List<OperandSource> sources, List<Kind> kinds, Profile profile) {
        Organism organism = Organism.create(simulation, START.clone(), 1000);
        simulation.addOrganism(organism);
        try {
            prepare(organism, environment, info, sources, kinds, profile);
            simulation.tick();
        } catch (SimulationFault fault) {
            throw new AssertionError(info.name() + " with operands " + kinds + " under profile " + profile
                    + " threw instead of failing: " + fault.getMessage(), fault);
        } finally {
            for (Organism each : new ArrayList<>(simulation.getOrganisms())) {
                each.kill("case done");
            }
            simulation.pruneDeadOrganisms();
        }
        if (organism.isInstructionFailed()) {
            assertThat(organism.getFailureReason())
                    .as("%s with operands %s under profile %s failed without a reason", info.name(), kinds, profile)
                    .isNotBlank();
        }
    }

    /**
     * Writes the instruction and its argument cells and puts the organism into the state the kinds
     * and the profile describe. Register operands name {@code %DRn} and {@code %LRn} with {@code n}
     * the slot index, so every slot has a register of its own.
     */
    private static void prepare(Organism organism, Environment environment, InstructionInfo info,
                                List<OperandSource> sources, List<Kind> kinds, Profile profile) {
        organism.setMr(profile == Profile.ZERO_MARKER ? 0 : 1);
        switch (profile) {
            case LOCATION_STACK_EMPTY -> { }
            case LOCATION_STACK_NO_POSITION -> organism.getLocationStack().push(LocationValue.NONE);
            case LOCATION_STACK_FULL -> {
                for (int i = 0; i < Config.LOCATION_STACK_MAX_DEPTH; i++) {
                    organism.getLocationStack().push(POSITION.clone());
                }
            }
            default -> organism.getLocationStack().push(POSITION.clone());
        }
        if (profile == Profile.DATA_STACK_FULL) {
            int stackOperands = (int) sources.stream().filter(source -> source == OperandSource.STACK).count();
            for (int i = 0; i < Config.DS_MAX_DEPTH - stackOperands; i++) {
                organism.getDataStack().push(scalar(1));
            }
        }

        environment.setMolecule(new Molecule(Config.TYPE_CODE, info.opcodeId()), organism.getId(), START);
        int[] cell = START.clone();
        // Stack operands are peeked from the top in slot order, so the last slot's value is pushed
        // first. A missing value leaves that slot and every slot after it off the stack.
        List<Object> stackValues = new ArrayList<>();
        for (int slot = 0; slot < sources.size(); slot++) {
            OperandSource source = sources.get(slot);
            Kind kind = kinds.get(slot);
            switch (source) {
                case STACK -> {
                    if (kind == Kind.MISSING) {
                        break;
                    }
                    stackValues.add(valueOf(kind));
                }
                case REGISTER -> {
                    int id = registerIdFor(kind, slot, RegisterBank.DR.base);
                    cell = nextCell(cell);
                    environment.setMolecule(new Molecule(Config.TYPE_DATA, id), organism.getId(), cell);
                    if (id == RegisterBank.DR.base + slot) {
                        organism.writeOperand(id, valueOf(kind));
                    }
                }
                case LOCATION_REGISTER -> {
                    int id = kind == Kind.DATA_REGISTER_ID
                            ? RegisterBank.DR.base + slot
                            : registerIdFor(kind, slot, RegisterBank.LR.base);
                    cell = nextCell(cell);
                    environment.setMolecule(new Molecule(Config.TYPE_DATA, id), organism.getId(), cell);
                    if (id == RegisterBank.LR.base + slot) {
                        organism.writeLocationOperand(id, (int[]) valueOf(kind));
                    }
                }
                case IMMEDIATE -> {
                    cell = nextCell(cell);
                    environment.setMolecule(Molecule.fromInt(scalar(immediateOf(kind))), organism.getId(), cell);
                }
                case VECTOR -> {
                    for (int component : (int[]) valueOf(kind)) {
                        cell = nextCell(cell);
                        environment.setMolecule(new Molecule(Config.TYPE_DATA, component), organism.getId(), cell);
                    }
                }
                case LABEL -> {
                    cell = nextCell(cell);
                    environment.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH), organism.getId(), cell);
                }
            }
        }
        for (int i = stackValues.size() - 1; i >= 0; i--) {
            organism.getDataStack().push(stackValues.get(i));
        }
    }

    private static int[] nextCell(int[] cell) {
        return new int[]{cell[0] + 1, cell[1]};
    }

    private static int registerIdFor(Kind kind, int slot, int bankBase) {
        return switch (kind) {
            case UNUSED_ID -> UNUSED_REGISTER_ID;
            case OUT_OF_TABLE_ID -> OUT_OF_TABLE_REGISTER_ID;
            default -> bankBase + slot;
        };
    }

    private static Object valueOf(Kind kind) {
        return switch (kind) {
            case SCALAR -> scalar(7);
            case VECTOR -> UNIT_VECTOR.clone();
            case ZERO_VECTOR -> ZERO_VECTOR.clone();
            case BIG_VECTOR -> BIG_VECTOR.clone();
            case ALL_MIN_VECTOR -> ALL_MIN_VECTOR.clone();
            case NO_POSITION -> LocationValue.NONE;
            case POSITION -> POSITION.clone();
            default -> throw new IllegalArgumentException(kind + " names no register or stack value");
        };
    }

    private static int immediateOf(Kind kind) {
        return switch (kind) {
            case IMMEDIATE_ZERO -> 0;
            case IMMEDIATE_SEVEN -> 7;
            case IMMEDIATE_NEGATIVE -> -3;
            default -> throw new IllegalArgumentException(kind + " names no immediate");
        };
    }

    private static Integer scalar(int value) {
        return new Molecule(Config.TYPE_DATA, value).toInt();
    }
}
