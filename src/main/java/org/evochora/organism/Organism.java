package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Logger;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.NopInstruction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

public class Organism {
    record ForkRequest(int[] childIp, int childEnergy, int[] childDv) {}
    public record FetchResult(int value, int[] nextIp) {}

    private final int id;
    private int[] ip;
    private int[] dp;
    private int[] dv;
    private int er;
    private final List<Object> drs;
    private final List<Object> prs;
    private final List<Object> fprs;
    private final Deque<Object> dataStack;
    private final Deque<Object> returnStack;
    private boolean isDead = false;
    private boolean loggingEnabled = false;
    private boolean instructionFailed = false;
    private String failureReason = null;

    // KORRIGIERT: Der ProcFrame sichert jetzt nur noch die PRs.
    public static final class ProcFrame {
        public final int[] relativeReturnIp;
        public final Object[] savedPrs;

        public ProcFrame(int[] relativeReturnIp, Object[] savedPrs) {
            this.relativeReturnIp = relativeReturnIp;
            this.savedPrs = savedPrs;
        }
    }
    private boolean skipIpAdvance = false;
    private int[] ipBeforeFetch;
    private int[] dvBeforeFetch;
    private final Logger logger;
    private final Simulation simulation;
    private final int[] initialPosition;
    private final Random random;

    Organism(int id, int[] startIp, int initialEnergy, Logger logger, Simulation simulation) {
        this.id = id;
        this.ip = startIp;
        this.dp = startIp;
        this.er = initialEnergy;
        this.logger = logger;
        this.simulation = simulation;
        this.dv = new int[startIp.length];
        this.dv[0] = 1;
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
        this.dataStack = new ArrayDeque<>(Config.STACK_MAX_DEPTH);
        this.returnStack = new ArrayDeque<>(Config.RS_MAX_DEPTH);
        this.ipBeforeFetch = Arrays.copyOf(startIp, startIp.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);
        this.initialPosition = Arrays.copyOf(startIp, startIp.length);
        this.random = new Random(id);
    }

    public static Organism create(Simulation simulation, int[] startIp, int initialEnergy, Logger logger) {
        int newId = simulation.getNextOrganismId();
        return new Organism(newId, startIp, initialEnergy, logger, simulation);
    }

    public Instruction planTick(World world) {
        this.instructionFailed = false;
        this.failureReason = null;
        this.skipIpAdvance = false;
        this.ipBeforeFetch = Arrays.copyOf(this.ip, this.ip.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);

        Symbol symbol = world.getSymbol(this.ip);
        if (Config.STRICT_TYPING) {
            if (symbol.type() != Config.TYPE_CODE && !symbol.isEmpty()) {
                this.instructionFailed("Illegal cell type (not CODE) at IP");
                return new NopInstruction(this, world.getSymbol(this.ip).toInt());
            }
        }
        int opcodeId = symbol.value();
        BiFunction<Organism, World, Instruction> planner = Instruction.getPlannerById(Config.TYPE_CODE | opcodeId);
        if (planner != null) {
            return planner.apply(this, world);
        }
        this.instructionFailed("Unknown opcode: " + opcodeId);
        return new NopInstruction(this, world.getSymbol(this.ip).toInt());
    }

    public void processTickAction(Instruction instruction, Simulation simulation) {
        if (isDead) return;
        World world = simulation.getWorld();
        List<Integer> rawArgs = getRawArgumentsFromWorld(ipBeforeFetch, instruction.getLength(), world);
        this.er -= instruction.getCost(this, world, rawArgs);
        instruction.execute(simulation);
        if (this.instructionFailed) {
            this.er -= Config.ERROR_PENALTY_COST;
        }
        if (this.er <= 0) {
            isDead = true;
            if (!this.instructionFailed) {
                this.instructionFailed("Ran out of energy");
            }
            return;
        }
        if (!this.skipIpAdvance) {
            advanceIpBy(instruction.getLength(), world);
        }
    }

    private List<Integer> getRawArgumentsFromWorld(int[] startIp, int instructionLength, World world) {
        List<Integer> rawArgs = new ArrayList<>();
        int[] tempIp = Arrays.copyOf(startIp, startIp.length);
        for (int i = 0; i < instructionLength - 1; i++) {
            tempIp = getNextInstructionPosition(tempIp, world, this.dvBeforeFetch);
            rawArgs.add(world.getSymbol(tempIp).toInt());
        }
        return rawArgs;
    }

    public FetchResult fetchArgument(int[] currentIp, World world) {
        int[] nextIp = getNextInstructionPosition(currentIp, world, this.dvBeforeFetch);
        Symbol symbol = world.getSymbol(nextIp);
        return new FetchResult(symbol.toInt(), nextIp);
    }

    public FetchResult fetchSignedArgument(int[] currentIp, World world) {
        int[] nextIp = getNextInstructionPosition(currentIp, world, this.dvBeforeFetch);
        Symbol symbol = world.getSymbol(nextIp);
        return new FetchResult(symbol.toScalarValue(), nextIp);
    }

    private void advanceIpBy(int steps, World world) {
        for (int i = 0; i < steps; i++) {
            this.ip = getNextInstructionPosition(this.ip, world, this.dvBeforeFetch);
        }
    }

    public int[] getNextInstructionPosition(int[] currentIp, World world, int[] directionVector) {
        int[] nextIp = new int[currentIp.length];
        for (int i = 0; i < currentIp.length; i++) {
            nextIp[i] = currentIp[i] + directionVector[i];
        }
        return world.getNormalizedCoordinate(nextIp);
    }

    public int[] getTargetCoordinate(int[] startPos, int[] vector, World world) {
        int[] targetPos = new int[startPos.length];
        for(int i=0; i<startPos.length; i++) {
            targetPos[i] = startPos[i] + vector[i];
        }
        return world.getNormalizedCoordinate(targetPos);
    }

    public void skipNextInstruction(World world) {
        int[] currentInstructionIp = this.getIpBeforeFetch();
        int currentInstructionOpcode = world.getSymbol(currentInstructionIp).toInt();
        int currentInstructionLength = Instruction.getInstructionLengthById(currentInstructionOpcode);

        int[] nextInstructionIp = currentInstructionIp;
        for (int i = 0; i < currentInstructionLength; i++) {
            nextInstructionIp = getNextInstructionPosition(nextInstructionIp, world, this.getDvBeforeFetch());
        }

        int nextOpcode = world.getSymbol(nextInstructionIp).toInt();
        int lengthToSkip = Instruction.getInstructionLengthById(nextOpcode);

        int[] finalIp = nextInstructionIp;
        for (int i = 0; i < lengthToSkip; i++) {
            finalIp = getNextInstructionPosition(finalIp, world, this.getDvBeforeFetch());
        }
        this.setIp(finalIp);
        this.setSkipIpAdvance(true);
    }

    public boolean isUnitVector(int[] vector) {
        if (vector.length != Config.WORLD_DIMENSIONS) {
            this.instructionFailed("Vector has incorrect dimensions: expected " + Config.WORLD_DIMENSIONS + ", got " + vector.length);
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

    public void instructionFailed(String reason) {
        this.instructionFailed = true;
        this.failureReason = reason;
    }

    // --- Public API für Instruktionen ---
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
    public Object getDr(int index) {
        if (index >= 0 && index < this.drs.size()) {
            return this.drs.get(index);
        }
        this.instructionFailed("DR index out of bounds: " + index);
        return null;
    }
    public void setIp(int[] newIp) { this.ip = newIp; }
    public void setDp(int[] newDp) { this.dp = newDp; }
    public void setDv(int[] newDv) { this.dv = newDv; }
    public void addEr(int amount) { this.er = Math.min(this.er + amount, Config.MAX_ORGANISM_ENERGY); }
    public void takeEr(int amount) { this.er -= amount; }
    public void setSkipIpAdvance(boolean skip) { this.skipIpAdvance = skip; }
    public int getId() { return id; }
    public int[] getIp() { return Arrays.copyOf(ip, ip.length); }
    public int[] getIpBeforeFetch() { return Arrays.copyOf(ipBeforeFetch, ipBeforeFetch.length); }
    public int[] getDp() { return Arrays.copyOf(dp, dp.length); }
    public int[] getDvBeforeFetch() { return Arrays.copyOf(dvBeforeFetch, dvBeforeFetch.length); }
    public int getEr() { return er; }
    public List<Object> getDrs() { return new ArrayList<>(drs); }
    public boolean isDead() { return isDead; }
    public boolean isLoggingEnabled() { return loggingEnabled; }
    public void setLoggingEnabled(boolean loggingEnabled) { this.loggingEnabled = loggingEnabled; }
    public boolean isInstructionFailed() { return instructionFailed; }
    public String getFailureReason() { return failureReason; }
    public Logger getLogger() { return logger; }
    public int[] getDv() { return Arrays.copyOf(dv, dv.length); }
    public Simulation getSimulation() { return simulation; }
    public int[] getInitialPosition() { return Arrays.copyOf(this.initialPosition, this.initialPosition.length); }
    public Deque<Object> getDataStack() { return this.dataStack; }
    public Deque<Object> getReturnStack() { return this.returnStack; }

    // PR register API
    public List<Object> getPrs() { return new ArrayList<>(this.prs); }
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
    public Object getPr(int index) {
        if (index >= 0 && index < this.prs.size()) {
            return this.prs.get(index);
        }
        this.instructionFailed("PR index out of bounds: " + index);
        return null;
    }
    public void restorePrs(Object[] snapshot) {
        if (snapshot == null || snapshot.length != this.prs.size()) {
            this.instructionFailed("Invalid PR snapshot size: expected " + this.prs.size() + ", got " + (snapshot == null ? "null" : snapshot.length));
            return;
        }
        for (int i = 0; i < snapshot.length; i++) {
            this.prs.set(i, snapshot[i]);
        }
    }

    // API für FPR-Register
    public List<Object> getFprs() { return new ArrayList<>(this.fprs); }
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
    public Object getFpr(int index) {
        if (index >= 0 && index < this.fprs.size()) {
            return this.fprs.get(index);
        }
        this.instructionFailed("FPR index out of bounds: " + index);
        return null;
    }
    public void restoreFprs(Object[] snapshot) {
        if (snapshot == null || snapshot.length != this.fprs.size()) {
            this.instructionFailed("Invalid FPR snapshot size: expected " + this.fprs.size() + ", got " + (snapshot == null ? "null" : snapshot.length));
            return;
        }
        for (int i = 0; i < snapshot.length; i++) {
            this.fprs.set(i, snapshot[i]);
        }
    }

    public Random getRandom() { return this.random; }
}