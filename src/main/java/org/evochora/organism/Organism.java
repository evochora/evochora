// src/main/java/org/evochora/organism/Organism.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Logger;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

public class Organism {
    // Hilfsklasse für Fork-Anfragen
    record ForkRequest(int[] childIp, int childEnergy, int[] childDv) {}

    public record FetchResult(int value, int[] nextIp) {}

    private final int id;
    private int[] ip;
    private int[] dp;
    private int[] dv;
    private int er;
    private final List<Object> drs;
    private boolean isDead = false;
    private boolean loggingEnabled = false;
    private ForkRequest forkRequestData = null;
    private boolean instructionFailed = false;
    private String failureReason = null;
    private boolean skipIpAdvance = false;
    private int[] ipBeforeFetch;
    private int[] dvBeforeFetch;
    private final Logger logger;
    private final Simulation simulation;
    private final int[] initialPosition;

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
        this.ipBeforeFetch = Arrays.copyOf(startIp, startIp.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);
        this.initialPosition = Arrays.copyOf(startIp, startIp.length);
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
                return NopInstruction.plan(this, world);
            }
        }
        int opcodeId = symbol.value();

        BiFunction<Organism, World, Instruction> planner = Instruction.getPlannerById(Config.TYPE_CODE | opcodeId);

        if (planner != null) {
            return planner.apply(this, world);
        }

        this.instructionFailed("Unknown opcode: " + opcodeId);
        return NopInstruction.plan(this, world);
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
            int length = instruction.getLength();
            advanceIpBy(length, world);
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

    boolean setDr(int index, Object value) {
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

    private FetchResult fetchArgumentInternal(int[] currentIp, World world, boolean signed) {
        int[] nextIp = getNextInstructionPosition(currentIp, world, this.dvBeforeFetch);
        Symbol symbol = world.getSymbol(nextIp);
        int value = signed ? symbol.toScalarValue() : symbol.value();
        return new FetchResult(value, nextIp);
    }

    public FetchResult fetchArgument(int[] currentIp, World world) {
        return fetchArgumentInternal(currentIp, world, false);
    }

    public FetchResult fetchSignedArgument(int[] currentIp, World world) {
        return fetchArgumentInternal(currentIp, world, true);
    }

    void advanceIpBy(int steps, World world) {
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

    int[] getTargetCoordinate(int[] startPos, int[] vector, World world) {
        int[] targetPos = new int[startPos.length];
        for(int i=0; i<startPos.length; i++) {
            targetPos[i] = startPos[i] + vector[i];
        }
        return world.getNormalizedCoordinate(targetPos);
    }

    void skipNextInstruction(World world) {
        int[] nextIp = getNextInstructionPosition(this.ip, world, this.dv);
        int opcodeToSkipFullId = world.getSymbol(nextIp).toInt();
        int lengthToSkip = Instruction.getInstructionLengthById(Config.TYPE_CODE | opcodeToSkipFullId);
        advanceIpBy(lengthToSkip, world);
    }

    boolean isUnitVector(int[] vector) {
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
        }
        return distance == 1;
    }

    public void instructionFailed(String reason) {
        this.instructionFailed = true;
        this.failureReason = reason;
    }

    void setIp(int[] newIp) { this.ip = newIp; }
    void setDp(int[] newDp) { this.dp = newDp; }
    void setDv(int[] newDv) { this.dv = newDv; }
    void addEr(int amount) { this.er = Math.min(this.er + amount, Config.MAX_ORGANISM_ENERGY); }
    void takeEr(int amount) { this.er -= amount; }
    void setForkRequestData(int[] childIp, int childEnergy, int[] childDv) { this.forkRequestData = new ForkRequest(childIp, childEnergy, childDv); }
    void setSkipIpAdvance(boolean skip) { this.skipIpAdvance = skip; }

    public int getId() { return id; }
    public int[] getIp() { return Arrays.copyOf(ip, ip.length); }
    public int[] getIpBeforeFetch() { return Arrays.copyOf(ipBeforeFetch, ipBeforeFetch.length); }
    public int[] getDp() { return Arrays.copyOf(dp, dp.length); }
    public int[] getDvBeforeFetch() { return Arrays.copyOf(dvBeforeFetch, dvBeforeFetch.length); }
    public int getEr() { return er; }
    public List<Object> getDrs() { return new ArrayList<>(drs); }
    public boolean isDead() { return isDead; }
    public ForkRequest getForkRequestData() { return forkRequestData; }
    public boolean isLoggingEnabled() { return loggingEnabled; }
    public void setLoggingEnabled(boolean loggingEnabled) { this.loggingEnabled = loggingEnabled; }
    public boolean isInstructionFailed() { return instructionFailed; }
    public String getFailureReason() { return failureReason; }
    public Logger getLogger() { return logger; }
    public int[] getDv() { return Arrays.copyOf(dv, dv.length); }
    public Simulation getSimulation() { return simulation; }
    public int[] getInitialPosition() { return Arrays.copyOf(this.initialPosition, this.initialPosition.length); }
}