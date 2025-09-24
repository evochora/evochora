package org.evochora.datapipeline.api.contracts;

import java.util.Arrays;

/**
 * A serializable representation of a procedure call frame.
 */
public class SerializableProcFrame {

    private String procedureName;
    private int[] returnAddress;
    private int[] savedProcedureRegisters;
    private int[] savedFormalParamRegisters;

    /**
     * Default constructor for deserialization.
     */
    public SerializableProcFrame() {
    }

    // Getters and setters

    public String getProcedureName() {
        return procedureName;
    }

    public void setProcedureName(String procedureName) {
        this.procedureName = procedureName;
    }

    public int[] getReturnAddress() {
        return returnAddress;
    }

    public void setReturnAddress(int[] returnAddress) {
        this.returnAddress = returnAddress;
    }

    public int[] getSavedProcedureRegisters() {
        return savedProcedureRegisters;
    }

    public void setSavedProcedureRegisters(int[] savedProcedureRegisters) {
        this.savedProcedureRegisters = savedProcedureRegisters;
    }

    public int[] getSavedFormalParamRegisters() {
        return savedFormalParamRegisters;
    }

    public void setSavedFormalParamRegisters(int[] savedFormalParamRegisters) {
        this.savedFormalParamRegisters = savedFormalParamRegisters;
    }

    @Override
    public String toString() {
        return "SerializableProcFrame{" +
                "procedureName='" + procedureName + '\'' +
                ", returnAddress=" + Arrays.toString(returnAddress) +
                ", savedProcedureRegisters=" + Arrays.toString(savedProcedureRegisters) +
                ", savedFormalParamRegisters=" + Arrays.toString(savedFormalParamRegisters) +
                '}';
    }
}
