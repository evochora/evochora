package org.evochora.datapipeline.api.contracts;

import java.util.List;

/**
 * Represents the complete internal and external state of a single organism.
 */
public class RawOrganismState {

    private int organismId;
    private Integer parentId;
    private int ownerId;
    private String programId;
    private int energy;
    private long birthTick;
    private boolean isDead;

    // VM State
    private int[] position;
    private int[] initialPosition;
    private List<int[]> dp;
    private int activeDp;
    private int[] dv;

    // Registers
    private int[] dataRegisters;
    private int[] procedureRegisters;
    private int[] formalParamRegisters;
    private List<int[]> locationRegisters;

    // Stacks
    private List<StackValue> dataStack;
    private List<int[]> locationStack;
    private List<SerializableProcFrame> callStack;

    // Error Information
    private OrganismErrorState errorState;

    /**
     * Default constructor for deserialization.
     */
    public RawOrganismState() {
    }

    // Getters and setters for all fields

    public int getOrganismId() {
        return organismId;
    }

    public void setOrganismId(int organismId) {
        this.organismId = organismId;
    }

    public Integer getParentId() {
        return parentId;
    }

    public void setParentId(Integer parentId) {
        this.parentId = parentId;
    }

    public int getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(int ownerId) {
        this.ownerId = ownerId;
    }

    public String getProgramId() {
        return programId;
    }

    public void setProgramId(String programId) {
        this.programId = programId;
    }

    public int getEnergy() {
        return energy;
    }

    public void setEnergy(int energy) {
        this.energy = energy;
    }

    public long getBirthTick() {
        return birthTick;
    }

    public void setBirthTick(long birthTick) {
        this.birthTick = birthTick;
    }

    public boolean isDead() {
        return isDead;
    }

    public void setDead(boolean dead) {
        isDead = dead;
    }

    public int[] getPosition() {
        return position;
    }

    public void setPosition(int[] position) {
        this.position = position;
    }

    public int[] getInitialPosition() {
        return initialPosition;
    }

    public void setInitialPosition(int[] initialPosition) {
        this.initialPosition = initialPosition;
    }

    public List<int[]> getDp() {
        return dp;
    }

    public void setDp(List<int[]> dp) {
        this.dp = dp;
    }

    public int getActiveDp() {
        return activeDp;
    }

    public void setActiveDp(int activeDp) {
        this.activeDp = activeDp;
    }

    public int[] getDv() {
        return dv;
    }

    public void setDv(int[] dv) {
        this.dv = dv;
    }

    public int[] getDataRegisters() {
        return dataRegisters;
    }

    public void setDataRegisters(int[] dataRegisters) {
        this.dataRegisters = dataRegisters;
    }

    public int[] getProcedureRegisters() {
        return procedureRegisters;
    }

    public void setProcedureRegisters(int[] procedureRegisters) {
        this.procedureRegisters = procedureRegisters;
    }

    public int[] getFormalParamRegisters() {
        return formalParamRegisters;
    }

    public void setFormalParamRegisters(int[] formalParamRegisters) {
        this.formalParamRegisters = formalParamRegisters;
    }

    public List<int[]> getLocationRegisters() {
        return locationRegisters;
    }

    public void setLocationRegisters(List<int[]> locationRegisters) {
        this.locationRegisters = locationRegisters;
    }

    public List<StackValue> getDataStack() {
        return dataStack;
    }

    public void setDataStack(List<StackValue> dataStack) {
        this.dataStack = dataStack;
    }

    public List<int[]> getLocationStack() {
        return locationStack;
    }

    public void setLocationStack(List<int[]> locationStack) {
        this.locationStack = locationStack;
    }

    public List<SerializableProcFrame> getCallStack() {
        return callStack;
    }

    public void setCallStack(List<SerializableProcFrame> callStack) {
        this.callStack = callStack;
    }

    public OrganismErrorState getErrorState() {
        return errorState;
    }

    public void setErrorState(OrganismErrorState errorState) {
        this.errorState = errorState;
    }
}
