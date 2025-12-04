package org.evochora.runtime.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;

/**
 * Represents a single programmable agent within the simulation.
 * <p>
 * An Organism is a virtual machine with its own set of registers, pointers, and stacks.
 * It executes a program defined by {@code CODE} molecules in the environment to interact
 * with the world, consume resources, and reproduce.
 */
public class Organism {
    /**
     * A record to hold information about a fork request.
     * @param childIp The initial IP of the child.
     * @param childEnergy The initial energy of the child.
     * @param childDv The initial DV of the child.
     */
    record ForkRequest(int[] childIp, int childEnergy, int[] childDv) {}
    /**
     * A record to hold the result of a fetch operation.
     * @param value The fetched value.
     * @param nextIp The IP of the next instruction.
     */
    public record FetchResult(int value, int[] nextIp) {}
    /**
     * A record to hold instruction execution data for history tracking.
     * @param opcodeId The opcode ID of the executed instruction.
     * @param rawArguments The raw argument values from the environment.
     * @param energyCost The total energy cost for executing this instruction.
     * @param registerValuesBefore Register values before instruction execution (for annotation display).
     *                             Maps register ID to register value (only for registers used as arguments).
     *                             Can be empty but never null.
     */
    public record InstructionExecutionData(
        int opcodeId,
        List<Integer> rawArguments,
        int energyCost,
        java.util.Map<Integer, Object> registerValuesBefore
    ) {}

    private final int id;
    private Integer parentId = null;
    private long birthTick = 0L;
    private String programId = "";
    private int[] ip;
    private final List<int[]> dps;
    private int activeDpIndex;
    private int[] dv;
    private int er;
    private final List<Object> drs;
    private final List<Object> prs;
    private final List<Object> fprs;
    private final List<Object> lrs;
    private final Deque<Object> dataStack;
    private final Deque<int[]> locationStack;
    private final Deque<ProcFrame> callStack;
    private boolean isDead = false;
    private boolean loggingEnabled = false;
    private boolean instructionFailed = false;
    private String failureReason = null;
    private Deque<ProcFrame> failureCallStack;

    /**
     * Represents a single frame on the call stack, created by a CALL instruction.
     * It stores the necessary state to return to the caller correctly.
     */
    public static final class ProcFrame {
        public final String procName;
        public final int[] absoluteReturnIp;
        public final int[] absoluteCallIp;
        public final Object[] savedPrs;
        public final Object[] savedFprs;
        public final java.util.Map<Integer, Integer> fprBindings;

        /**
         * Constructs a new ProcFrame.
         * @param procName The name of the procedure.
         * @param absoluteReturnIp The absolute return IP.
         * @param absoluteCallIp The absolute address of the CALL instruction that created this frame.
         * @param savedPrs The saved PRs.
         * @param savedFprs The saved FPRs.
         * @param fprBindings The FPR bindings.
         */
        public ProcFrame(String procName, int[] absoluteReturnIp, int[] absoluteCallIp, Object[] savedPrs, Object[] savedFprs, java.util.Map<Integer, Integer> fprBindings) {
            this.procName = procName;
            this.absoluteReturnIp = absoluteReturnIp;
            this.absoluteCallIp = absoluteCallIp;
            this.savedPrs = savedPrs;
            this.savedFprs = savedFprs;
            this.fprBindings = fprBindings;
        }
    }
    private boolean skipIpAdvance = false;
    private int[] ipBeforeFetch;
    private int[] dvBeforeFetch;
    private InstructionExecutionData lastInstructionExecution = null;
    private final Logger logger;
    private final Simulation simulation;
    private final int[] initialPosition;
    private final Random random;
    

    /**
     * Constructs a new Organism. This constructor should only be called via the static factory {@link #create}.
     *
     * @param id The unique identifier for this organism.
     * @param startIp The initial coordinate of the Instruction Pointer (IP).
     * @param initialEnergy The starting energy (ER) of the organism.
     * @param logger The logger instance for logging events.
     * @param simulation The simulation instance this organism belongs to.
     */
    Organism(int id, int[] startIp, int initialEnergy, Logger logger, Simulation simulation) {
        this.id = id;
        this.ip = startIp;
        this.dps = new ArrayList<>(Config.NUM_DATA_POINTERS);
        for (int i = 0; i < Config.NUM_DATA_POINTERS; i++) {
            this.dps.add(Arrays.copyOf(startIp, startIp.length));
        }
        this.er = initialEnergy;
        this.logger = logger;
        this.simulation = simulation;
        this.dv = new int[startIp.length];
        this.dv[0] = 1; // Default direction: +X
        this.drs = new ArrayList<>(Config.NUM_DATA_REGISTERS);
        for (int i = 0; i < Config.NUM_DATA_REGISTERS; i++) {
            this.drs.add(0);
        }
        this.prs = new ArrayList<>(Config.NUM_PROC_REGISTERS);
        for (int i = 0; i < Config.NUM_PROC_REGISTERS; i++) {
            this.prs.add(0);
        }
        this.fprs = new ArrayList<>(Config.NUM_FORMAL_PARAM_REGISTERS);
        for (int i = 0; i < Config.NUM_FORMAL_PARAM_REGISTERS; i++) {
            this.fprs.add(0);
        }
        this.lrs = new ArrayList<>(Config.NUM_LOCATION_REGISTERS);
        for (int i = 0; i < Config.NUM_LOCATION_REGISTERS; i++) {
            this.lrs.add(new int[startIp.length]);
        }
        this.locationStack = new ArrayDeque<>(Config.LOCATION_STACK_MAX_DEPTH);
        this.dataStack = new ArrayDeque<>(Config.STACK_MAX_DEPTH);
        this.callStack = new ArrayDeque<>(Config.CALL_STACK_MAX_DEPTH);
        this.activeDpIndex = 0;
        this.ipBeforeFetch = Arrays.copyOf(startIp, startIp.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);
        this.initialPosition = Arrays.copyOf(startIp, startIp.length);
        IRandomProvider baseProvider = simulation.getRandomProvider();
        if (baseProvider != null) {
            this.random = baseProvider.deriveFor("organism", id).asJavaRandom();
        } else {
            this.random = new Random(id);
        }
        
    }

    /**
     * Factory method to create a new Organism with a unique ID from the simulation.
     *
     * @param simulation The simulation instance.
     * @param startIp The initial coordinate of the Instruction Pointer.
     * @param initialEnergy The starting energy.
     * @param logger The logger instance.
     * @return A newly created Organism.
     */
    public static Organism create(Simulation simulation, int[] startIp, int initialEnergy, Logger logger) {
        int newId = simulation.getNextOrganismId();
        return new Organism(newId, startIp, initialEnergy, logger, simulation);
    }

    /**
     * Resets the organism's per-tick state. Called by the VirtualMachine before planning a new instruction.
     */
    public void resetTickState() {
        this.instructionFailed = false;
        this.failureReason = null;
        this.failureCallStack = null;
        this.skipIpAdvance = false;
        this.ipBeforeFetch = java.util.Arrays.copyOf(this.ip, this.ip.length);
        this.dvBeforeFetch = java.util.Arrays.copyOf(this.dv, this.dv.length);
        this.lastInstructionExecution = null;
    }

    /**
     * Advances the Instruction Pointer by a given number of steps along the current direction vector.
     *
     * @param steps The number of steps to advance.
     * @param environment The simulation environment.
     */
    public void advanceIpBy(int steps, Environment environment) {
        for (int i = 0; i < steps; i++) {
            this.ip = getNextInstructionPosition(this.ip, this.dvBeforeFetch, environment);
        }
    }

    /**
     * Retrieves the raw integer values of an instruction's arguments from the environment.
     *
     * @param instructionLength The total length of the instruction (opcode + arguments).
     * @param environment The simulation environment.
     * @return A list of raw integer values representing the arguments.
     */
    public List<Integer> getRawArgumentsFromEnvironment(int instructionLength, Environment environment) {
        List<Integer> rawArgs = new ArrayList<>();
        int[] tempIp = Arrays.copyOf(this.ipBeforeFetch, this.ipBeforeFetch.length);
        for (int i = 0; i < instructionLength - 1; i++) {
            tempIp = getNextInstructionPosition(tempIp, this.dvBeforeFetch, environment);
            rawArgs.add(environment.getMolecule(tempIp).toInt());
        }
        return rawArgs;
    }

    /**
     * Marks the organism as dead and records the reason.
     *
     * @param reason The reason for death.
     */
    public void kill(String reason) {
        this.isDead = true;
        if (!this.instructionFailed) {
            instructionFailed(reason);
        }
    }

    /**
     * Checks if the IP should not be advanced automatically at the end of a tick.
     * This is typically true after a jump or call instruction.
     *
     * @return {@code true} if the IP advance should be skipped.
     */
    public boolean shouldSkipIpAdvance() {
        return skipIpAdvance;
    }

    /**
     * Fetches the value of an instruction argument from the cell following the given coordinate.
     *
     * @param currentIp The coordinate of the preceding molecule (opcode or another argument).
     * @param environment The simulation environment.
     * @return A {@link FetchResult} containing the fetched value and the coordinate of the next molecule.
     */
    public FetchResult fetchArgument(int[] currentIp, Environment environment) {
        int[] nextIp = getNextInstructionPosition(currentIp, this.dvBeforeFetch, environment);
        Molecule molecule = environment.getMolecule(nextIp);
        return new FetchResult(molecule.toInt(), nextIp);
    }

    /**
     * Fetches the signed scalar value of an instruction argument from the cell following the given coordinate.
     *
     * @param currentIp The coordinate of the preceding molecule.
     * @param environment The simulation environment.
     * @return A {@link FetchResult} containing the signed scalar value and the coordinate of the next molecule.
     */
    public FetchResult fetchSignedArgument(int[] currentIp, Environment environment) {
        int[] nextIp = getNextInstructionPosition(currentIp, this.dvBeforeFetch, environment);
        Molecule molecule = environment.getMolecule(nextIp);
        return new FetchResult(molecule.toScalarValue(), nextIp);
    }

    /**
     * Calculates the coordinate of the next instruction or argument based on the current position and direction.
     *
     * @param currentIp The current coordinate.
     * @param directionVector The direction vector to apply.
     * @param environment The simulation environment (for normalization).
     * @return The normalized coordinate of the next molecule.
     */
    public int[] getNextInstructionPosition(int[] currentIp, int[] directionVector, Environment environment) {
        return environment.properties.getNextPosition(currentIp, directionVector);
    }

    /**
     * Calculates an absolute target coordinate by adding a vector to a starting position.
     *
     * @param startPos The starting coordinate.
     * @param vector The vector to add.
     * @param environment The simulation environment (for normalization).
     * @return The normalized target coordinate.
     */
    public int[] getTargetCoordinate(int[] startPos, int[] vector, Environment environment) {
        return environment.properties.getTargetCoordinate(startPos, vector);
    }

    /**
     * Skips the instruction immediately following the currently executing one.
     *
     * @param environment The simulation environment.
     */
    public void skipNextInstruction(Environment environment) {
        int[] currentInstructionIp = this.getIpBeforeFetch();
        int currentInstructionOpcode = environment.getMolecule(currentInstructionIp).toInt();
        int currentInstructionLength = Instruction.getInstructionLengthById(currentInstructionOpcode, environment);

        int[] nextInstructionIp = currentInstructionIp;
        for (int i = 0; i < currentInstructionLength; i++) {
            nextInstructionIp = getNextInstructionPosition(nextInstructionIp, this.getDvBeforeFetch(), environment);
        }

        int nextOpcode = environment.getMolecule(nextInstructionIp).toInt();
        int lengthToSkip = Instruction.getInstructionLengthById(nextOpcode, environment);

        int[] finalIp = nextInstructionIp;
        for (int i = 0; i < lengthToSkip; i++) {
            finalIp = getNextInstructionPosition(finalIp, this.getDvBeforeFetch(), environment);
        }
        this.setIp(finalIp);
        this.setSkipIpAdvance(true);
    }

    /**
     * Validates if a given vector is a unit vector (sum of absolute components is 1).
     *
     * @param vector The vector to check.
     * @return {@code true} if it is a unit vector, otherwise {@code false}.
     */
    public boolean isUnitVector(int[] vector) {
        int expected = this.simulation.getEnvironment().getShape().length;
        if (vector.length != expected) {
            this.instructionFailed("Vector has incorrect dimensions: expected " + expected + ", got " + vector.length);
            return false;
        }
        int distance = 0;
        for (int component : vector) {
            distance += Math.abs(component);
        }
        if (distance != 1) {
            this.instructionFailed("Vector is not a unit vector (sum of abs components is " + distance + ")");
            return false;
        }
        return true;
    }

    /**
     * Sets the instruction-failed flag and records the reason.
     *
     * @param reason The reason for the failure.
     */
    public void instructionFailed(String reason) {
        if (!this.instructionFailed) {
            this.instructionFailed = true;
            this.failureReason = reason;
            if (this.callStack != null && !this.callStack.isEmpty()) {
                this.failureCallStack = new ArrayDeque<>(this.callStack);
            }
        }
    }

    // --- Public API for Instructions ---

    /**
     * Sets the value of a Data Register (DR).
     *
     * @param index The index of the register (0-7).
     * @param value The value to set (must be {@code Integer} or {@code int[]}).
     * @return {@code true} on success, {@code false} on failure.
     */
    public boolean setDr(int index, Object value) {
        if (index >= 0 && index < this.drs.size()) {
            if (value instanceof Integer || value instanceof int[]) {
                this.drs.set(index, value);
                return true;
            }
            this.instructionFailed("Attempted to set unsupported type " + (value != null ? value.getClass().getSimpleName() : "null") + " to DR " + index);
            return false;
        }
        this.instructionFailed("DR index out of bounds: " + index);
        return false;
    }

    /**
     * Gets the value of a Data Register (DR).
     *
     * @param index The index of the register (0-7).
     * @return The value of the register, or {@code null} if the index is invalid.
     */
    public Object getDr(int index) {
        if (index >= 0 && index < this.drs.size()) {
            return this.drs.get(index);
        }
        this.instructionFailed("DR index out of bounds: " + index);
        return null;
    }

    /**
     * Sets the Instruction Pointer (IP) to a new coordinate.
     *
     * @param newIp The new coordinate for the IP.
     */
    public void setIp(int[] newIp) { 
        this.ip = newIp; 
    }

    /**
     * Sets the coordinate of a specific Data Pointer (DP).
     *
     * @param index The index of the DP to modify.
     * @param newDp The new coordinate to set.
     * @return {@code true} on success, {@code false} on failure (e.g., invalid index).
     */
    public boolean setDp(int index, int[] newDp) {
        if (index >= 0 && index < this.dps.size()) {
            this.dps.set(index, newDp);
            return true;
        }
        this.instructionFailed("DP index out of bounds: " + index);
        return false;
    }

    /**
     * Gets the coordinate of a specific Data Pointer (DP).
     *
     * @param index The index of the DP to retrieve.
     * @return A copy of the DP's coordinate, or {@code null} if the index is invalid.
     */
    public int[] getDp(int index) {
        if (index >= 0 && index < this.dps.size()) {
            return Arrays.copyOf(dps.get(index), dps.get(index).length);
        }
        this.instructionFailed("DP index out of bounds: " + index);
        return null;
    }

    /**
     * Gets the index of the currently active Data Pointer (DP).
     * @return the active DP index.
     */
    public int getActiveDpIndex() {
        return this.activeDpIndex;
    }

    /**
     * Sets the active Data Pointer (DP) index.
     *
     * @param index Index to activate (0..NUM_DATA_POINTERS-1)
     * @return {@code true} if successful; {@code false} if out of bounds
     */
    public boolean setActiveDpIndex(int index) {
        if (index >= 0 && index < this.dps.size()) {
            this.activeDpIndex = index;
            return true;
        }
        this.instructionFailed("Active DP index out of bounds: " + index);
        return false;
    }

    /**
     * Returns a copy of the active DP coordinate.
     * @return a copy of the active DP coordinate.
     */
    public int[] getActiveDp() {
        return getDp(this.activeDpIndex);
    }

    /**
     * Sets the active DP coordinate.
     * @param newDp The new coordinate.
     * @return true if successful, false otherwise.
     */
    public boolean setActiveDp(int[] newDp) {
        return setDp(this.activeDpIndex, newDp);
    }

    /**
     * Gets a list of all Data Pointers (DPs).
     *
     * @return A new list containing copies of all DP coordinates.
     */
    public List<int[]> getDps() {
        return this.dps.stream()
                .map(dp -> Arrays.copyOf(dp, dp.length))
                .collect(Collectors.toList());
    }

    /**
     * Sets the Direction Vector (DV).
     *
     * @param newDv The new direction vector.
     */
    public void setDv(int[] newDv) { 
        this.dv = newDv; 
    }

    /**
     * Adds energy to the organism's Energy Register (ER), clamped to the maximum allowed.
     *
     * @param amount The amount of energy to add.
     */
    public void addEr(int amount) { 
        this.er = Math.min(this.er + amount, Config.MAX_ORGANISM_ENERGY); 
    }

    /**
     * Subtracts energy from the organism's Energy Register (ER).
     *
     * @param amount The amount of energy to subtract.
     */
    public void takeEr(int amount) { 
        this.er -= amount; 
    }

    /**
     * Sets a flag to prevent the VM from automatically advancing the IP at the end of the tick.
     *
     * @param skip {@code true} to skip the IP advance.
     */
    public void setSkipIpAdvance(boolean skip) { this.skipIpAdvance = skip; }

    /** @return The unique ID of the organism. */
    public int getId() { return id; }
    /** @return The ID of the parent organism, or {@code null} if it has no parent. */
    public Integer getParentId() { return parentId; }
    /** Sets the ID of the parent organism.
     * @param parentId The parent ID.
     */
    public void setParentId(Integer parentId) { 
        this.parentId = parentId;
    }

    /**
     * Checks if a cell, identified by its owner's ID, is accessible to this organism.
     * A cell is considered accessible if it is owned by the organism itself or its direct parent.
     *
     * @param ownerId The ID of the cell's owner.
     * @return {@code true} if the cell is accessible, otherwise {@code false}.
     */
    public boolean isCellAccessible(int ownerId) {
        // A cell is always accessible to its owner.
        if (ownerId == this.id) {
            return true;
        }
        // A cell is also accessible if it belongs to the direct parent.
        Integer parent = this.getParentId();
        if (parent != null && ownerId == parent) {
            return true;
        }
        // Otherwise, it's considered foreign.
        return false;
    }

    /** @return The simulation tick number at which the organism was born. */
    public long getBirthTick() { return birthTick; }
    /** Sets the birth tick of the organism.
     * @param birthTick The birth tick.
     */
    public void setBirthTick(long birthTick) { 
        this.birthTick = birthTick;
    }
    /** @return The program ID associated with this organism. */
    public String getProgramId() { return programId; }
    /** Sets the program ID for this organism.
     * @param programId The program ID.
     */
    public void setProgramId(String programId) { 
        this.programId = programId;
    }
    /** @return A copy of the current Instruction Pointer (IP) coordinate. */
    public int[] getIp() { return Arrays.copyOf(ip, ip.length); }
    /** @return A copy of the IP coordinate as it was at the beginning of the tick. */
    public int[] getIpBeforeFetch() { return Arrays.copyOf(ipBeforeFetch, ipBeforeFetch.length); }
    /** @return A copy of the DV as it was at the beginning of the tick. */
    public int[] getDvBeforeFetch() { return Arrays.copyOf(dvBeforeFetch, dvBeforeFetch.length); }
    /** @return The instruction execution data from the last executed instruction, or null if no instruction was executed. */
    public InstructionExecutionData getLastInstructionExecution() { return lastInstructionExecution; }
    /** Sets the instruction execution data for the last executed instruction.
     * @param data The instruction execution data to store.
     */
    public void setLastInstructionExecution(InstructionExecutionData data) { this.lastInstructionExecution = data; }
    /** @return The current energy level (ER). */
    public int getEr() { return er; }
    /** @return A copy of the list of Data Registers (DRs). */
    public List<Object> getDrs() { return new ArrayList<>(drs); }
    /** @return true if the organism is dead, false otherwise. */
    public boolean isDead() { return isDead; }
    /** @return true if detailed logging is enabled for this organism. */
    public boolean isLoggingEnabled() { return loggingEnabled; }
    /** Enables or disables detailed logging for this organism.
     * @param loggingEnabled true to enable logging, false to disable.
     */
    public void setLoggingEnabled(boolean loggingEnabled) { this.loggingEnabled = loggingEnabled; }
    /** @return true if the last instruction failed. */
    public boolean isInstructionFailed() { return instructionFailed; }
    /** @return The reason for the last instruction failure. */
    public String getFailureReason() { return failureReason; }
    /** @return The logger instance. */
    public Logger getLogger() { return logger; }
    /** @return A copy of the current Direction Vector (DV). */
    public int[] getDv() { return Arrays.copyOf(dv, dv.length); }
    /** @return The simulation instance. */
    public Simulation getSimulation() { return simulation; }
    /** @return A copy of the organism's initial starting position. */
    public int[] getInitialPosition() { return Arrays.copyOf(this.initialPosition, this.initialPosition.length); }
    /** @return A reference to the Data Stack (DS). */
    public Deque<Object> getDataStack() { 
        return this.dataStack;
    }
    /** @return A reference to the Call Stack (CS). */
    public Deque<ProcFrame> getCallStack() { 
        return this.callStack;
    }

    /** @return A copy of the list of Procedure-local Registers (PRs). */
    public List<Object> getPrs() { return new ArrayList<>(this.prs); }

    /**
     * Sets the value of a Procedure-local Register (PR).
     *
     * @param index The index of the register.
     * @param value The value to set.
     * @return {@code true} on success, {@code false} on failure.
     */
    public boolean setPr(int index, Object value) {
        if (index >= 0 && index < this.prs.size()) {
            if (value instanceof Integer || value instanceof int[]) {
                this.prs.set(index, value);
                return true;
            }
            this.instructionFailed("Attempted to set unsupported type " + (value != null ? value.getClass().getSimpleName() : "null") + " to PR " + index);
            return false;
        }
        this.instructionFailed("PR index out of bounds: " + index);
        return false;
    }

    /**
     * Gets the value of a Procedure-local Register (PR).
     *
     * @param index The index of the register.
     * @return The value, or {@code null} on failure.
     */
    public Object getPr(int index) {
        if (index >= 0 && index < this.prs.size()) {
            return this.prs.get(index);
        }
        this.instructionFailed("PR index out of bounds: " + index);
        return null;
    }

    /**
     * Restores the state of all PRs from a snapshot array.
     *
     * @param snapshot The snapshot to restore from.
     */
    public void restorePrs(Object[] snapshot) {
        if (snapshot == null || snapshot.length != this.prs.size()) {
            this.instructionFailed("Invalid PR snapshot size: expected " + this.prs.size() + ", got " + (snapshot == null ? "null" : snapshot.length));
            return;
        }
        for (int i = 0; i < snapshot.length; i++) {
            this.prs.set(i, snapshot[i]);
        }
    }

    /** @return A copy of the list of Formal Parameter Registers (FPRs). */
    public List<Object> getFprs() { return new ArrayList<>(this.fprs); }

    /**
     * Sets the value of a Formal Parameter Register (FPR).
     *
     * @param index The index of the register.
     * @param value The value to set.
     * @return {@code true} on success, {@code false} on failure.
     */
    public boolean setFpr(int index, Object value) {
        if (index >= 0 && index < this.fprs.size()) {
            if (value instanceof Integer || value instanceof int[]) {
                this.fprs.set(index, value);
                return true;
            }
            this.instructionFailed("Attempted to set unsupported type " + (value != null ? value.getClass().getSimpleName() : "null") + " to FPR " + index);
            return false;
        }
        this.instructionFailed("FPR index out of bounds: " + index);
        return false;
    }

    /**
     * Gets the value of a Formal Parameter Register (FPR).
     *
     * @param index The index of the register.
     * @return The value, or {@code null} on failure.
     */
    public Object getFpr(int index) {
        if (index >= 0 && index < this.fprs.size()) {
            return this.fprs.get(index);
        }
        this.instructionFailed("FPR index out of bounds: " + index);
        return null;
    }

    /**
     * Restores the state of all FPRs from a snapshot array.
     *
     * @param snapshot The snapshot to restore from.
     */
    public void restoreFprs(Object[] snapshot) {
        if (snapshot == null || snapshot.length > this.fprs.size()) {
            this.instructionFailed("Invalid FPR snapshot for restore");
            return;
        }
        for (int i = 0; i < snapshot.length; i++) {
            this.fprs.set(i, snapshot[i]);
        }
    }

    /**
     * Gets a copy of the list of Location Registers (LRs).
     *
     * @return A new list containing the vector values of all LRs.
     */
    public List<Object> getLrs() {
        return new ArrayList<>(this.lrs);
    }

    /**
     * Sets the value of a Location Register (LR).
     *
     * @param index The index of the register.
     * @param value The vector value to set.
     * @return {@code true} on success, {@code false} on failure.
     */
    public boolean setLr(int index, int[] value) {
        if (index >= 0 && index < this.lrs.size()) {
            this.lrs.set(index, value);
            return true;
        }
        this.instructionFailed("LR index out of bounds: " + index);
        return false;
    }

    /**
     * Gets the value of a Location Register (LR).
     *
     * @param index The index of the register.
     * @return The vector value, or {@code null} on failure.
     */
    public int[] getLr(int index) {
        if (index >= 0 && index < this.lrs.size()) {
            return (int[]) this.lrs.get(index);
        }
        this.instructionFailed("LR index out of bounds: " + index);
        return null;
    }

    /**
     * Gets a reference to the Location Stack (LS).
     *
     * @return The location stack.
     */
    public Deque<int[]> getLocationStack() {
        return this.locationStack;
    }

    /**
     * Gets the organism-specific random number generator.
     *
     * @return The {@link Random} instance.
     */
    public Random getRandom() { return this.random; }

    /**
     * Gets the call stack as it was at the moment an instruction failure occurred.
     *
     * @return A copy of the call stack at the time of failure.
     */
    public Deque<ProcFrame> getFailureCallStack() { return this.failureCallStack; }

    /**
     * Reads a value from a register (DR, PR, or FPR) using its full numeric ID.
     *
     * @param id The full ID of the register (e.g., 5 for DR5, 1002 for PR2).
     * @return The value read from the register.
     */
    public Object readOperand(int id) {
        if (id >= Instruction.FPR_BASE) return getFpr(id - Instruction.FPR_BASE);
        if (id >= Instruction.PR_BASE) return getPr(id - Instruction.PR_BASE);
        return getDr(id);
    }

    /**
     * Writes a value to a register (DR, PR, or FPR) using its full numeric ID.
     *
     * @param id The full ID of the register.
     * @param value The value to write.
     * @return {@code true} if the write was successful.
     */
    public boolean writeOperand(int id, Object value) {
        if (id >= Instruction.FPR_BASE) return setFpr(id - Instruction.FPR_BASE, value);
        if (id >= Instruction.PR_BASE) return setPr(id - Instruction.PR_BASE, value);
        return setDr(id, value);
    }
    
}