package org.evochora.runtime.model;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

public class Organism {
    private static final Logger LOG = LoggerFactory.getLogger(Organism.class);
    record ForkRequest(int[] childIp, int childEnergy, int[] childDv) {}
    public record FetchResult(int value, int[] nextIp) {}

    private final int id;
    private Integer parentId = null;
    private long birthTick = 0L;
    private String programId = "";
    private int[] ip;
    private int[] dp;
    private int[] dv;
    private int er;
    private final List<Object> drs;
    private final List<Object> prs;
    private final List<Object> fprs;
    private final Deque<Object> dataStack;
    private final Deque<ProcFrame> callStack;
    private boolean isDead = false;
    private boolean loggingEnabled = false;
    private boolean instructionFailed = false;
    private String failureReason = null;
    private Deque<ProcFrame> failureCallStack;

    // Suchen Sie nach der inneren Klasse "ProcFrame"
    public static final class ProcFrame {
        public final String procName;
        public final int[] absoluteReturnIp; // Früher: relativeReturnIp
        public final Object[] savedPrs;
        public final Object[] savedFprs;
        public final java.util.Map<Integer, Integer> fprBindings;

        public ProcFrame(String procName, int[] absoluteReturnIp, Object[] savedPrs, Object[] savedFprs, java.util.Map<Integer, Integer> fprBindings) { // UND HIER
            this.procName = procName;
            this.absoluteReturnIp = absoluteReturnIp; // Früher: relativeReturnIp
            this.savedPrs = savedPrs;
            this.savedFprs = savedFprs;
            this.fprBindings = fprBindings;
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
        this.callStack = new ArrayDeque<>(Config.CALL_STACK_MAX_DEPTH);
        this.ipBeforeFetch = Arrays.copyOf(startIp, startIp.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);
        this.initialPosition = Arrays.copyOf(startIp, startIp.length);
        this.random = new Random(id);
    }

    public static Organism create(Simulation simulation, int[] startIp, int initialEnergy, Logger logger) {
        int newId = simulation.getNextOrganismId();
        return new Organism(newId, startIp, initialEnergy, logger, simulation);
    }

    /**
     * Setzt den internen Zustand für einen neuen Ausführungs-Tick zurück.
     * Dies wird von der VirtualMachine aufgerufen, bevor eine neue Instruktion geplant wird.
     */
    public void resetTickState() {
        this.instructionFailed = false;
        this.failureReason = null;
        this.failureCallStack = null;
        this.skipIpAdvance = false;
        this.ipBeforeFetch = java.util.Arrays.copyOf(this.ip, this.ip.length);
        this.dvBeforeFetch = java.util.Arrays.copyOf(this.dv, this.dv.length);
    }

    public void advanceIpBy(int steps, Environment environment) {
        for (int i = 0; i < steps; i++) {
            this.ip = getNextInstructionPosition(this.ip, environment, this.dvBeforeFetch);
        }
    }

    public List<Integer> getRawArgumentsFromEnvironment(int instructionLength, Environment environment) {
        List<Integer> rawArgs = new ArrayList<>();
        int[] tempIp = Arrays.copyOf(this.ipBeforeFetch, this.ipBeforeFetch.length);
        for (int i = 0; i < instructionLength - 1; i++) {
            tempIp = getNextInstructionPosition(tempIp, environment, this.dvBeforeFetch);
            rawArgs.add(environment.getMolecule(tempIp).toInt());
        }
        return rawArgs;
    }

    public void kill(String reason) {
        this.isDead = true;
        if (!this.instructionFailed) {
            instructionFailed(reason);
        }
    }

    public boolean shouldSkipIpAdvance() {
        return skipIpAdvance;
    }

    public FetchResult fetchArgument(int[] currentIp, Environment environment) {
        int[] nextIp = getNextInstructionPosition(currentIp, environment, this.dvBeforeFetch);
        Molecule molecule = environment.getMolecule(nextIp);
        return new FetchResult(molecule.toInt(), nextIp);
    }

    public FetchResult fetchSignedArgument(int[] currentIp, Environment environment) {
        int[] nextIp = getNextInstructionPosition(currentIp, environment, this.dvBeforeFetch);
        Molecule molecule = environment.getMolecule(nextIp);
        return new FetchResult(molecule.toScalarValue(), nextIp);
    }

    public int[] getNextInstructionPosition(int[] currentIp, Environment environment, int[] directionVector) {
        int[] nextIp = new int[currentIp.length];
        for (int i = 0; i < currentIp.length; i++) {
            nextIp[i] = currentIp[i] + directionVector[i];
        }
        return environment.getNormalizedCoordinate(nextIp);
    }

    public int[] getTargetCoordinate(int[] startPos, int[] vector, Environment environment) {
        int[] targetPos = new int[startPos.length];
        for(int i=0; i<startPos.length; i++) {
            targetPos[i] = startPos[i] + vector[i];
        }
        return environment.getNormalizedCoordinate(targetPos);
    }

    public void skipNextInstruction(Environment environment) {
        int[] currentInstructionIp = this.getIpBeforeFetch();
        int currentInstructionOpcode = environment.getMolecule(currentInstructionIp).toInt();
        int currentInstructionLength = Instruction.getInstructionLengthById(currentInstructionOpcode);

        int[] nextInstructionIp = currentInstructionIp;
        for (int i = 0; i < currentInstructionLength; i++) {
            nextInstructionIp = getNextInstructionPosition(nextInstructionIp, environment, this.getDvBeforeFetch());
        }

        int nextOpcode = environment.getMolecule(nextInstructionIp).toInt();
        int lengthToSkip = Instruction.getInstructionLengthById(nextOpcode);

        int[] finalIp = nextInstructionIp;
        for (int i = 0; i < lengthToSkip; i++) {
            finalIp = getNextInstructionPosition(finalIp, environment, this.getDvBeforeFetch());
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
        if (!this.instructionFailed) { // Nur beim ersten Fehler den Stack speichern
            this.instructionFailed = true;
            this.failureReason = reason;
            // NEU: Speichere eine Kopie des Return Stacks im Fehlerfall
            if (this.callStack != null && !this.callStack.isEmpty()) {
                this.failureCallStack = new ArrayDeque<>(this.callStack);
            }
        }
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
    public Integer getParentId() { return parentId; }
    public void setParentId(Integer parentId) { this.parentId = parentId; }
    public long getBirthTick() { return birthTick; }
    public void setBirthTick(long birthTick) { this.birthTick = birthTick; }
    public String getProgramId() { return programId; }
    public void setProgramId(String programId) { this.programId = programId; }
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
    public Deque<ProcFrame> getCallStack() { return this.callStack; }

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
            instructionFailed("Attempted to set unsupported type " + (value != null ? value.getClass().getSimpleName() : "null") + " to FPR " + index);
            return false;
        }
        instructionFailed("FPR index out of bounds: " + index);
        return false;
    }
    public Object getFpr(int index) {
        if (index >= 0 && index < this.fprs.size()) {
            return this.fprs.get(index);
        }
        instructionFailed("FPR index out of bounds: " + index);
        return null;
    }
    public void restoreFprs(Object[] snapshot) {
        if (snapshot == null || snapshot.length > this.fprs.size()) {
            instructionFailed("Invalid FPR snapshot for restore");
            return;
        }
        for (int i = 0; i < snapshot.length; i++) {
            this.fprs.set(i, snapshot[i]);
        }
    }

    public Random getRandom() { return this.random; }

    public Deque<ProcFrame> getFailureCallStack() { return this.failureCallStack; }

    // --- NEUE ZENTRALE OPERANDEN-METHODEN ---

    /**
     * Liest einen Wert aus einem Register, egal ob DR, PR oder FPR.
     * @param id die volle ID des Registers (z.B. 5 für DR5, 1002 für PR2).
     * @return der gelesene Wert.
     */
    public Object readOperand(int id) {
        if (id >= Instruction.FPR_BASE) return getFpr(id - Instruction.FPR_BASE);
        if (id >= Instruction.PR_BASE) return getPr(id - Instruction.PR_BASE);
        return getDr(id);
    }

    /**
     * Schreibt einen Wert in ein Register, egal ob DR, PR oder FPR.
     * @param id die volle ID des Registers.
     * @param value der zu schreibende Wert.
     * @return true, wenn das Schreiben erfolgreich war.
     */
    public boolean writeOperand(int id, Object value) {
        if (id >= Instruction.FPR_BASE) return setFpr(id - Instruction.FPR_BASE, value);
        if (id >= Instruction.PR_BASE) return setPr(id - Instruction.PR_BASE, value);
        return setDr(id, value);
    }
}