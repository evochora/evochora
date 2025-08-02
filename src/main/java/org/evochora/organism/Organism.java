// src/main/java/org/evochora/organism/Organism.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.organism.*;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

record ForkRequest(int[] childIp, int childEnergy, int[] childDv) {}

public class Organism {
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
    private boolean skipIpAdvance = false;
    private int[] ipBeforeFetch;
    private int[] dvBeforeFetch; // HINZUGEFÜGT

    Organism(int id, int[] startIp, int initialEnergy) {
        this.id = id;
        this.ip = startIp;
        this.dp = startIp;
        this.er = initialEnergy;
        this.dv = new int[startIp.length];
        this.dv[0] = 1;
        this.drs = new ArrayList<>(Config.NUM_DATA_REGISTERS);
        for (int i = 0; i < Config.NUM_DATA_REGISTERS; i++) {
            this.drs.add(0);
        }
        this.ipBeforeFetch = Arrays.copyOf(startIp, startIp.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length); // HINZUGEFÜGT
    }

    public static Organism create(Simulation simulation, int[] startIp, int initialEnergy) {
        int newId = simulation.getNextOrganismId();
        return new Organism(newId, startIp, initialEnergy);
    }

    public Action planTick(World world, Map<Integer, BiFunction<Organism, World, Action>> plannerRegistry) {
        this.instructionFailed = false;
        this.skipIpAdvance = false;
        this.ipBeforeFetch = Arrays.copyOf(this.ip, this.ip.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length); // HINZUGEFÜGT

        Symbol symbol = world.getSymbol(this.ip);
        if (symbol.type() != Config.TYPE_CODE && symbol.toInt() != 0) {
            this.instructionFailed = true;
            return NopAction.plan(this, world);
        }
        int opcode = symbol.toInt();

        BiFunction<Organism, World, Action> planner = plannerRegistry.get(opcode);

        if (planner != null) {
            return planner.apply(this, world);
        }

        this.instructionFailed = true;
        return NopAction.plan(this, world);
    }

    public void executeAction(Action action, Simulation simulation) {
        if (isDead) return;

        World world = simulation.getWorld();
        int opcode = world.getSymbol(ip).toInt();

        this.er -= Config.OPCODE_COSTS.getOrDefault(opcode, 1);

        action.execute(simulation);

        if (this.instructionFailed) {
            this.er -= Config.ERROR_PENALTY_COST;
        }
        if (this.er <= 0) {
            isDead = true;
            return;
        }

        if (!this.skipIpAdvance) {
            Config.Opcode opcodeDef = Config.OPCODE_DEFINITIONS.get(opcode);
            int length = (opcodeDef != null) ? opcodeDef.length() : 1;

            // KORRIGIERT: Verwendet den DV, der zu Beginn des Ticks galt.
            advanceIpBy(length, world);
        }
    }

    // --- Package-Private Methoden für Actions ---

    int fetchArgument(int[] readHead, World world) {
        // Verwendet den DV, der zu Beginn des Ticks galt, für eine konsistente Lese-Operation
        int[] nextReadHead = getNextInstructionPosition(readHead, world, this.dvBeforeFetch);
        System.arraycopy(nextReadHead, 0, readHead, 0, readHead.length);
        return world.getSymbol(readHead).value();
    }

    int fetchSignedArgument(int[] readHead, World world) {
        int[] nextReadHead = getNextInstructionPosition(readHead, world, this.dvBeforeFetch);
        System.arraycopy(nextReadHead, 0, readHead, 0, readHead.length);
        return world.getSymbol(readHead).toInt();
    }

    boolean setDr(int index, Object value) {
        if (index >= 0 && index < this.drs.size()) { this.drs.set(index, value); return true; }
        this.instructionFailed = true; return false;
    }

    Object getDr(int index) {
        if (index >= 0 && index < this.drs.size()) { return this.drs.get(index); }
        this.instructionFailed = true; return null;
    }

    void advanceIpBy(int steps, World world) {
        for (int i = 0; i < steps; i++) {
            // KORRIGIERT: Verwendet den DV, der zu Beginn des Ticks galt.
            this.ip = getNextInstructionPosition(this.ip, world, this.dvBeforeFetch);
        }
    }

    int[] getNextInstructionPosition(int[] currentIp, World world, int[] directionVector) {
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
        int opcodeToSkip = world.getSymbol(nextIp).toInt();
        Config.Opcode opcodeDef = Config.OPCODE_DEFINITIONS.get(opcodeToSkip);
        int lengthToSkip = (opcodeDef != null) ? opcodeDef.length() : 1;
        advanceIpBy(lengthToSkip, world);
    }

    boolean isUnitVector(int[] vector) {
        int distance = 0;
        for (int component : vector) {
            distance += Math.abs(component);
        }
        return distance == 1;
    }

    void instructionFailed() { this.instructionFailed = true; }
    void setIp(int[] newIp) { this.ip = newIp; }
    void setDp(int[] newDp) { this.dp = newDp; }
    void setDv(int[] newDv) { this.dv = newDv; }
    void addEr(int amount) { this.er = Math.min(this.er + amount, Config.MAX_ORGANISM_ENERGY); }
    void takeEr(int amount) { this.er -= amount; }
    void setForkRequestData(int[] childIp, int childEnergy, int[] childDv) { this.forkRequestData = new ForkRequest(childIp, childEnergy, childDv); }
    void setSkipIpAdvance(boolean skip) { this.skipIpAdvance = skip; }

    // --- Öffentliche Getter ---
    public int getId() { return id; }
    public int[] getIp() { return Arrays.copyOf(ip, ip.length); }
    public int[] getIpBeforeFetch() { return Arrays.copyOf(ipBeforeFetch, ipBeforeFetch.length); }
    public int[] getDp() { return Arrays.copyOf(dp, dp.length); }
    public int[] getDv() { return Arrays.copyOf(dv, dv.length); }
    public int getEr() { return er; }
    public List<Object> getDrs() { return new ArrayList<>(drs); }
    public boolean isDead() { return isDead; }
    public ForkRequest getForkRequestData() { return forkRequestData; }
    public boolean isLoggingEnabled() { return loggingEnabled; }
    public void setLoggingEnabled(boolean loggingEnabled) { this.loggingEnabled = loggingEnabled; }
}