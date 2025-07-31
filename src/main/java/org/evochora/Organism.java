// src/main/java/org/evochora/Organism.java
package org.evochora;

import org.evochora.organism.actions.Action;
import org.evochora.organism.actions.NopAction;
import org.evochora.organism.actions.SetlAction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Organism {
    private final int id;
    private int[] ip;
    private int[] dp;
    private int[] dv;
    private int er;
    private final List<Object> drs;
    private boolean isDead = false;
    private boolean loggingEnabled = false;
    private Object forkRequestData = null;

    private Organism(int id, int[] startIp, int initialEnergy) {
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
    }

    public static Organism create(Simulation simulation, int[] startIp, int initialEnergy) {
        int newId = simulation.getNextOrganismId();
        return new Organism(newId, startIp, initialEnergy);
    }

    // --- Private Methoden ---
    private void setDr(int index, Object value) {
        if (index >= 0 && index < this.drs.size()) {
            this.drs.set(index, value);
        }
    }

    private void advanceIpBy(int steps, World world) {
        for (int i = 0; i < steps; i++) {
            int[] newIp = new int[this.ip.length];
            for (int d = 0; d < this.ip.length; d++) {
                newIp[d] = this.ip[d] + this.dv[d];
            }
            this.ip = world.getNormalizedCoordinate(newIp);
        }
    }

    private int[] getNextInstructionPosition(int[] currentIp, World world) {
        int[] nextIp = new int[currentIp.length];
        for (int i = 0; i < currentIp.length; i++) {
            nextIp[i] = currentIp[i] + this.dv[i];
        }
        return world.getNormalizedCoordinate(nextIp);
    }

    // --- Logik für den Zwei-Phasen-Zyklus ---
    // TODO: Implement logic for complete instruction set
    public Action planTick(World world) {
        int opcode = world.getSymbol(this.ip);

        if (opcode == Config.OP_SETL) {
            int[] regArgPos = getNextInstructionPosition(this.ip, world);
            int[] valArgPos = getNextInstructionPosition(regArgPos, world);

            int registerIndex = world.getSymbol(regArgPos);
            int literalValue = world.getSymbol(valArgPos);

            return new SetlAction(this, registerIndex, literalValue);
        }

        return new NopAction(this);
    }

    public void executeAction(Action action, World world) {
        // Die zentrale Logik, die Aktionen ausführt
        if (action instanceof SetlAction setlAction) {
            this.setDr(setlAction.getRegisterIndex(), setlAction.getLiteralValue());
            this.advanceIpBy(Config.OPCODE_LENGTHS.get(Config.OP_SETL), world);
        } else if (action instanceof NopAction) {
            this.advanceIpBy(Config.OPCODE_LENGTHS.get(Config.OP_NOP), world);
        }
        // Hier kommen später weitere `instanceof` für andere Aktionen hinzu
    }

    // --- Öffentliche Getter & der eine Setter ---
    public int getId() { return id; }
    public int[] getIp() { return Arrays.copyOf(ip, ip.length); }
    public int[] getDp() { return Arrays.copyOf(dp, dp.length); }
    public int[] getDv() { return Arrays.copyOf(dv, dv.length); }
    public int getEr() { return er; }
    public List<Object> getDrs() { return new ArrayList<>(drs); }
    public boolean isDead() { return isDead; }
    public Object getForkRequestData() { return forkRequestData; }
    public boolean isLoggingEnabled() { return loggingEnabled; }
    public void setLoggingEnabled(boolean loggingEnabled) { this.loggingEnabled = loggingEnabled; }
}