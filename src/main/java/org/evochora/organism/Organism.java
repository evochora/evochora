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
    private String failureReason = null;
    private boolean skipIpAdvance = false;
    private int[] ipBeforeFetch;
    private int[] dvBeforeFetch;
    private final Logger logger;

    Organism(int id, int[] startIp, int initialEnergy, Logger logger) {
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
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);
        this.logger = logger;
    }

    public static Organism create(Simulation simulation, int[] startIp, int initialEnergy, Logger logger) {
        int newId = simulation.getNextOrganismId();
        return new Organism(newId, startIp, initialEnergy, logger);
    }

    public Action planTick(World world, Map<Integer, BiFunction<Organism, World, Action>> plannerRegistry) {
        this.instructionFailed = false;
        this.failureReason = null;
        this.skipIpAdvance = false;
        this.ipBeforeFetch = Arrays.copyOf(this.ip, this.ip.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);

        Symbol symbol = world.getSymbol(this.ip);
        if (Config.STRICT_TYPING) {
            if (symbol.type() != Config.TYPE_CODE && symbol.toInt() != 0) {
                this.instructionFailed("Illegal cell type (not CODE) at IP");
                return NopAction.plan(this, world);
            }
        }
        int opcode = symbol.value();

        BiFunction<Organism, World, Action> planner = plannerRegistry.get(Config.TYPE_CODE | opcode);

        if (planner != null) {
            return planner.apply(this, world);
        }

        this.instructionFailed("Unknown opcode: " + opcode);
        return NopAction.plan(this, world);
    }

    // NEU HINZUGEFÜGT: Diese Methode kapselt die Ausführungslogik für einen Tick.
    // Sie wird von Simulation.tick() aufgerufen.
    public void processTickAction(Action action, Simulation simulation) {
        if (isDead) return; // Organismus ist tot, tue nichts

        World world = simulation.getWorld();

        // Kosten für den Opcode abziehen
        int opcodeFullValue = world.getSymbol(ipBeforeFetch).toInt(); // Verwende IP_BEFORE_FETCH für den Opcode selbst
        int opcodeCostLookup = (opcodeFullValue == 0) ? 0 : Symbol.fromInt(opcodeFullValue).value();
        this.er -= Config.OPCODE_COSTS.getOrDefault(Config.TYPE_CODE | opcodeCostLookup, 1);

        // Spezifische Logik der Aktion ausführen
        action.execute(simulation);

        // Nach Ausführung der spezifischen Aktion, prüfen, ob sie fehlgeschlagen ist
        if (this.instructionFailed) {
            this.er -= Config.ERROR_PENALTY_COST;
        }

        // Prüfen, ob Organismus tot ist
        if (this.er <= 0) {
            isDead = true;
            if (!this.instructionFailed) { // Wenn noch kein anderer Fehlergrund gesetzt wurde
                this.instructionFailed("Ran out of energy");
            }
            return; // Organismus stirbt, kein IP-Vorrücken
        }

        // IP vorrücken, es sei denn, die Aktion hat es übersprungen (z.B. JUMP, FORK)
        if (!this.skipIpAdvance) {
            Config.Opcode opcodeDef = Config.OPCODE_DEFINITIONS.get(opcodeFullValue);
            int length = (opcodeDef != null) ? opcodeDef.length() : 1;
            advanceIpBy(length, world);
        }
    }

    // --- Package-Private Methoden für Actions ---

    int fetchArgument(int[] readHead, World world) {
        int[] nextReadHead = getNextInstructionPosition(readHead, world, this.dvBeforeFetch);
        System.arraycopy(nextReadHead, 0, readHead, 0, readHead.length);
        return world.getSymbol(readHead).value();
    }

    int fetchSignedArgument(int[] readHead, World world) {
        int[] nextReadHead = getNextInstructionPosition(readHead, world, this.dvBeforeFetch);
        System.arraycopy(nextReadHead, 0, readHead, 0, readHead.length);
        return world.getSymbol(readHead).value();
    }

    boolean setDr(int index, Object value) {
        if (index >= 0 && index < this.drs.size()) { this.drs.set(index, value); return true; }
        this.instructionFailed("DR index out of bounds: " + index); return false;
    }

    Object getDr(int index) {
        if (index >= 0 && index < this.drs.size()) { return this.drs.get(index); }
        this.instructionFailed("DR index out of bounds: " + index); return null;
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
        int opcodeToSkip = world.getSymbol(nextIp).toInt();
        Config.Opcode opcodeDef = Config.OPCODE_DEFINITIONS.get(opcodeToSkip);
        int lengthToSkip = (opcodeDef != null) ? opcodeDef.length() : 1;
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

    // --- Öffentliche Getter ---
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
}