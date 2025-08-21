package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Implements n-dimensional vector manipulation instructions: VGET, VSET, and VBLD.
 */
public class VectorInstruction extends Instruction {

    public VectorInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        try {
            String opName = getName();
            List<Operand> operands = resolveOperands(context.getWorld());

            int dims = context.getWorld().getShape().length;
            switch (opName) {
                case "VGTR", "VGTI" -> handleVectorGet(operands);
                case "VGTS" -> handleVectorGetStack();
                case "VSTR", "VSTI" -> handleVectorSet(operands);
                case "VSTS" -> handleVectorSetStack();
                case "VBLD" -> handleVectorBuild(operands, dims);
                case "VBLS" -> handleVectorBuildStack(dims);
                default -> organism.instructionFailed("Unknown vector instruction: " + opName);
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during vector operation.");
        } catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
            organism.instructionFailed("Invalid operand types for vector operation: " + e.getMessage());
        }
    }

    private void handleVectorGet(List<Operand> operands) {
        if (operands.size() != 3) {
            organism.instructionFailed(getName() + " requires 3 operands.");
            return;
        }
        int destReg = operands.get(0).rawSourceId();
        if (!(operands.get(1).value() instanceof int[] vector)) {
            organism.instructionFailed(getName() + " source must be a vector.");
            return;
        }
        if (!(operands.get(2).value() instanceof Integer indexVal)) {
            organism.instructionFailed(getName() + " index must be a scalar.");
            return;
        }
        int index = Molecule.fromInt(indexVal).toScalarValue();

        if (index < 0 || index >= vector.length) {
            organism.instructionFailed("Vector index out of bounds: " + index);
            return;
        }

        writeOperand(destReg, new Molecule(Config.TYPE_DATA, vector[index]).toInt());
    }

    private void handleVectorGetStack() {
        Deque<Object> ds = organism.getDataStack();
        if (ds.size() < 2) {
            organism.instructionFailed("VGTS requires an index and a vector on the stack.");
            return;
        }
        Object indexObj = ds.pop();
        Object vecObj = ds.pop();

        if (!(vecObj instanceof int[] vector)) {
            organism.instructionFailed("VGTS requires a vector on the stack.");
            return;
        }
        if (!(indexObj instanceof Integer indexVal)) {
            organism.instructionFailed("VGTS requires a scalar index on the stack.");
            return;
        }
        int index = Molecule.fromInt(indexVal).toScalarValue();

        if (index < 0 || index >= vector.length) {
            organism.instructionFailed("Vector index out of bounds: " + index);
            return;
        }

        ds.push(new Molecule(Config.TYPE_DATA, vector[index]).toInt());
    }

    private void handleVectorSet(List<Operand> operands) {
        if (operands.size() != 3) {
            organism.instructionFailed(getName() + " requires 3 operands.");
            return;
        }
        int vecReg = operands.get(0).rawSourceId();
        if (!(readOperand(vecReg) instanceof int[] vector)) {
            organism.instructionFailed(getName() + " target must be a vector register.");
            return;
        }
        if (!(operands.get(1).value() instanceof Integer indexVal)) {
            organism.instructionFailed(getName() + " index must be a scalar.");
            return;
        }
        if (!(operands.get(2).value() instanceof Integer valueVal)) {
            organism.instructionFailed(getName() + " value must be a scalar.");
            return;
        }
        int index = Molecule.fromInt(indexVal).toScalarValue();
        int value = Molecule.fromInt(valueVal).toScalarValue();

        if (index < 0 || index >= vector.length) {
            organism.instructionFailed("Vector index out of bounds: " + index);
            return;
        }

        int[] newVector = Arrays.copyOf(vector, vector.length);
        newVector[index] = value;
        writeOperand(vecReg, newVector);
    }

    private void handleVectorSetStack() {
        Deque<Object> ds = organism.getDataStack();
        if (ds.size() < 3) {
            organism.instructionFailed("VSTS requires a value, an index, and a vector on the stack.");
            return;
        }
        Object valObj = ds.pop();
        Object idxObj = ds.pop();
        Object vecObj = ds.pop();

        if (!(vecObj instanceof int[] vector)) {
            organism.instructionFailed("VSTS requires a vector on the stack.");
            return;
        }
        if (!(idxObj instanceof Integer indexVal)) {
            organism.instructionFailed("VSTS requires a scalar index on the stack.");
            return;
        }
        if (!(valObj instanceof Integer valueVal)) {
            organism.instructionFailed("VSTS requires a scalar value on the stack.");
            return;
        }
        int index = Molecule.fromInt(indexVal).toScalarValue();
        int value = Molecule.fromInt(valueVal).toScalarValue();

        if (index < 0 || index >= vector.length) {
            organism.instructionFailed("Vector index out of bounds: " + index);
            return;
        }

        int[] newVector = Arrays.copyOf(vector, vector.length);
        newVector[index] = value;
        ds.push(newVector);
    }

    private void handleVectorBuild(List<Operand> operands, int dims) {
        if (operands.size() != 1) {
            organism.instructionFailed("VBLD requires one destination register operand.");
            return;
        }
        int destReg = operands.get(0).rawSourceId();
        Deque<Object> ds = organism.getDataStack();
        if (ds.size() < dims) {
            organism.instructionFailed("Stack underflow for VBLD. Need " + dims + " components.");
            return;
        }

        int[] newVector = new int[dims];

        // Das erste gepoppte Element (X-Wert) kommt an Index 0.
        for (int i = 0; i < dims; i++) {
            Object valObj = ds.pop();
            if (!(valObj instanceof Integer val)) {
                organism.instructionFailed("VBLD requires scalar components on the stack.");
                return;
            }
            newVector[i] = Molecule.fromInt(val).toScalarValue();
        }

        writeOperand(destReg, newVector);
    }

    private void handleVectorBuildStack(int dims) {
        Deque<Object> ds = organism.getDataStack();
        if (ds.size() < dims) {
            organism.instructionFailed("Stack underflow for VBLS. Need " + dims + " components.");
            return;
        }

        int[] newVector = new int[dims];

        for (int i = 0; i < dims; i++) {
            Object valObj = ds.pop();
            if (!(valObj instanceof Integer val)) {
                organism.instructionFailed("VBLS requires scalar components on the stack.");
                return;
            }
            newVector[i] = Molecule.fromInt(val).toScalarValue();
        }

        ds.push(newVector);
    }
}