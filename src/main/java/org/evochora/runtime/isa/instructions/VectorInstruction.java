package org.evochora.runtime.isa.instructions;

import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.NoSuchElementException;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * Implements n-dimensional vector manipulation instructions, including VGET, VSET, VBLD,
 * bit-to-vector/vector-to-bit conversions, and vector rotation.
 */
public class VectorInstruction extends Instruction {

    private static int family;

    /**
     * Registers all vector manipulation instructions with the instruction registry.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operation 0: VGT (Vector get)
        reg(0, Variant.RRR, "VGTR", REGISTER, REGISTER, REGISTER);
        reg(0, Variant.RRI, "VGTI", REGISTER, REGISTER, IMMEDIATE);
        reg(0, Variant.SS, "VGTS", STACK, STACK);  // index, vector
        // Operation 1: VST (Vector set)
        reg(1, Variant.RRR, "VSTR", REGISTER, REGISTER, REGISTER);
        reg(1, Variant.RII, "VSTI", REGISTER, IMMEDIATE, IMMEDIATE);
        reg(1, Variant.SSS, "VSTS", STACK, STACK, STACK);  // value, index, vector
        // Operation 2: VBL (Vector build)
        reg(2, Variant.R, "VBLD", REGISTER);
        reg(2, Variant.NONE, "VBLS");
        // Operation 3: B2V (Bytes to vector)
        reg(3, Variant.RR, "B2VR", REGISTER, REGISTER);
        reg(3, Variant.RI, "B2VI", REGISTER, IMMEDIATE);
        reg(3, Variant.S, "B2VS", STACK);  // mask
        // Operation 4: V2B (Vector to bytes)
        reg(4, Variant.RR, "V2BR", REGISTER, REGISTER);
        reg(4, Variant.RV, "V2BI", REGISTER, VECTOR);
        reg(4, Variant.S, "V2BS", STACK);  // vector
        // Operation 5: RTR (Retarget / Rotate Right by 90 degrees in plane of two axes)
        reg(5, Variant.RRR, "RTRR", REGISTER, REGISTER, REGISTER);
        reg(5, Variant.RII, "RTRI", REGISTER, IMMEDIATE, IMMEDIATE);
        reg(5, Variant.SSS, "RTRS", STACK, STACK, STACK);  // axis2, axis1, vector
    }

    private static void reg(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(VectorInstruction.class, VectorInstruction::new, family, op, variant, name, true, sources);
    }

    /**
     * Constructs a new VectorInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public VectorInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        try {
            String opName = getName();
            List<Operand> operands = resolveOperands(context.getWorld());
            if (organism.isInstructionFailed()) {
                return;
            }

            int dims = context.getWorld().getShape().length;
            switch (opName) {
                case "VGTR", "VGTI" -> handleVectorGet(operands);
                case "VGTS" -> handleVectorGetStack(operands);
                case "VSTR", "VSTI" -> handleVectorSet(operands);
                case "VSTS" -> handleVectorSetStack(operands);
                case "VBLD" -> handleVectorBuild(operands, dims);
                case "VBLS" -> handleVectorBuildStack(dims);  // Cannot use operands - dynamic count based on dims
                case "B2VR", "B2VI" -> handleBitToVector(operands, dims);
                case "B2VS" -> handleBitToVectorStack(operands, dims);
                case "V2BR", "V2BI" -> handleVectorToBit(operands);
                case "V2BS" -> handleVectorToBitStack(operands);
                case "RTRR", "RTRI" -> handleVectorRotate(operands);
                case "RTRS" -> handleVectorRotateStack(operands);
                default -> organism.instructionFailed("Unknown vector instruction: " + opName);
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during vector operation.");
        } catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
            organism.instructionFailed("Invalid operand types for vector operation: " + e.getMessage());
        }
    }

    private void handleVectorRotate(List<Operand> operands) {
        // Expect 3 operands: dest/source vector register, axis1 (scalar), axis2 (scalar)
        if (operands.size() != 3) {
            organism.instructionFailed(getName() + " requires 3 operands.");
            return;
        }
        int vecReg = operands.getFirst().rawSourceId();
        Object vecObj = operands.getFirst().value();
        if (!(vecObj instanceof int[] vector)) {
            organism.instructionFailed(getName() + " target must be a vector register.");
            return;
        }
        Integer ax1Raw = extractScalar(operands.get(1).value());
        Integer ax2Raw = extractScalar(operands.get(2).value());
        if (ax1Raw == null || ax2Raw == null) {
            organism.instructionFailed(getName() + " axes must be scalars.");
            return;
        }
        int axis1 = ax1Raw;
        int axis2 = ax2Raw;
        if (!validateAxes(axis1, axis2, vector.length)) {
            // On failure, do not modify the vector register
            return;
        }
        int[] rotated = Arrays.copyOf(vector, vector.length);
        int vi = vector[axis1];
        int vj = vector[axis2];
        rotated[axis1] = vj;
        rotated[axis2] = -vi;
        writeOperand(vecReg, rotated);
    }

    private void handleVectorRotateStack(List<Operand> operands) {
        // Operands order from stack (first popped = first in list): axis2, axis1, vector
        if (operands.size() < 3) {
            organism.instructionFailed("RTRS requires axis2, axis1, and a vector on the stack.");
            return;
        }
        Deque<Object> ds = organism.getDataStack();
        Object axis2Obj = operands.get(0).value();
        Object axis1Obj = operands.get(1).value();
        Object vecObj = operands.get(2).value();
        if (!(vecObj instanceof int[] vector)) {
            organism.instructionFailed("RTRS requires a vector on the stack.");
            return;
        }
        Integer ax1Raw = extractScalar(axis1Obj);
        Integer ax2Raw = extractScalar(axis2Obj);
        if (ax1Raw == null || ax2Raw == null) {
            organism.instructionFailed("RTRS axes must be scalars.");
            // Push original vector back to keep it unchanged as per spec
            ds.push(vector);
            return;
        }
        int axis1 = ax1Raw;
        int axis2 = ax2Raw;
        if (!validateAxes(axis1, axis2, vector.length)) {
            // Failure: push original vector back, unchanged
            ds.push(vector);
            return;
        }
        int[] rotated = Arrays.copyOf(vector, vector.length);
        int vi = vector[axis1];
        int vj = vector[axis2];
        rotated[axis1] = vj;
        rotated[axis2] = -vi;
        ds.push(rotated);
    }

    private Integer extractScalar(Object obj) {
        if (!(obj instanceof Integer iv)) return null;
        return Molecule.fromInt(iv).toScalarValue();
    }

    private boolean validateAxes(int axis1, int axis2, int dims) {
        if (axis1 < 0 || axis1 >= dims || axis2 < 0 || axis2 >= dims) {
            organism.instructionFailed("Axis index out of bounds.");
            return false;
        }
        if (axis1 == axis2) {
            organism.instructionFailed("Axes must be distinct.");
            return false;
        }
        return true;
    }

    private void handleBitToVector(List<Operand> operands, int dims) {
        if (operands.size() != 2) {
            organism.instructionFailed("B2V requires two operands.");
            return;
        }
        int destReg = operands.get(0).rawSourceId();
        if (!(operands.get(1).value() instanceof Integer srcVal)) {
            organism.instructionFailed("B2V source must be scalar mask.");
            return;
        }
        org.evochora.runtime.model.Molecule srcMol = org.evochora.runtime.model.Molecule.fromInt(srcVal);
        int mask = srcMol.toScalarValue();
        int[] vec = maskToUnitVector(mask, dims);
        if (vec == null) {
            organism.instructionFailed("B2V requires a single-bit direction mask.");
            return;
        }
        writeOperand(destReg, vec);
    }

    private void handleBitToVectorStack(List<Operand> operands, int dims) {
        // Operands: mask (single operand from stack)
        if (operands.isEmpty()) {
            organism.instructionFailed("B2VS requires a mask on the stack.");
            return;
        }
        Object top = operands.get(0).value();
        if (!(top instanceof Integer iv)) {
            organism.instructionFailed("B2VS requires a scalar mask on the stack.");
            return;
        }
        org.evochora.runtime.model.Molecule srcMol = org.evochora.runtime.model.Molecule.fromInt(iv);
        int[] vec = maskToUnitVector(srcMol.toScalarValue(), dims);
        if (vec == null) {
            organism.instructionFailed("B2VS requires a single-bit direction mask.");
            return;
        }
        Deque<Object> ds = organism.getDataStack();
        ds.push(vec);
    }

    private void handleVectorToBit(List<Operand> operands) {
        if (operands.size() != 2) {
            organism.instructionFailed("V2B requires two operands.");
            return;
        }
        int destReg = operands.get(0).rawSourceId();
        Object src = operands.get(1).value();
        int mask = vectorToMask(src);
        if (mask == -1) {
            organism.instructionFailed("V2B requires a unit vector with single non-zero component of magnitude 1.");
            return;
        }
        writeOperand(destReg, new Molecule(Config.TYPE_DATA, mask).toInt());
    }

    private void handleVectorToBitStack(List<Operand> operands) {
        // Operands: vector (single operand from stack)
        if (operands.isEmpty()) {
            organism.instructionFailed("V2BS requires a vector on the stack.");
            return;
        }
        Object top = operands.get(0).value();
        int mask = vectorToMask(top);
        if (mask == -1) {
            organism.instructionFailed("V2BS requires a unit vector with single non-zero component of magnitude 1.");
            return;
        }
        Deque<Object> ds = organism.getDataStack();
        ds.push(new Molecule(Config.TYPE_DATA, mask).toInt());
    }

    private int vectorToMask(Object vectorObj) {
        if (!(vectorObj instanceof int[] vector)) {
            return -1;
        }
        int nonZeroIndex = -1;
        for (int i = 0; i < vector.length; i++) {
            int v = vector[i];
            if (v == 0) continue;
            if (Math.abs(v) != 1) return -1;
            if (nonZeroIndex != -1) return -1;
            nonZeroIndex = i;
        }
        if (nonZeroIndex == -1) return -1;
        boolean positive = vector[nonZeroIndex] > 0;
        int bitIndex = nonZeroIndex * 2 + (positive ? 0 : 1);
        if (nonZeroIndex >= Config.VALUE_BITS / 2) return -1;
        return 1 << bitIndex;
    }

    private int[] maskToUnitVector(int rawMask, int dims) {
        int mask = rawMask & ((1 << org.evochora.runtime.Config.VALUE_BITS) - 1);
        if (Integer.bitCount(mask) != 1) {
            return null;
        }
        int bit = Integer.numberOfTrailingZeros(mask);
        int[] vec = new int[dims];
        int axis = bit / 2;
        int dirBit = bit % 2;
        if (axis >= dims || axis >= Config.VALUE_BITS / 2) {
            return null;
        }
        vec[axis] = (dirBit == 0) ? 1 : -1;
        return vec;
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

    private void handleVectorGetStack(List<Operand> operands) {
        // Operands order from stack (first popped = first in list): index, vector
        if (operands.size() < 2) {
            organism.instructionFailed("VGTS requires an index and a vector on the stack.");
            return;
        }
        Object indexObj = operands.get(0).value();
        Object vecObj = operands.get(1).value();

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

        Deque<Object> ds = organism.getDataStack();
        ds.push(new Molecule(Config.TYPE_DATA, vector[index]).toInt());
    }

    private void handleVectorSet(List<Operand> operands) {
        if (operands.size() != 3) {
            organism.instructionFailed(getName() + " requires 3 operands.");
            return;
        }
        int vecReg = operands.get(0).rawSourceId();
        if (!(operands.get(0).value() instanceof int[] vector)) {
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

    private void handleVectorSetStack(List<Operand> operands) {
        // Operands order from stack (first popped = first in list): value, index, vector
        if (operands.size() < 3) {
            organism.instructionFailed("VSTS requires a value, an index, and a vector on the stack.");
            return;
        }
        Object valObj = operands.get(0).value();
        Object idxObj = operands.get(1).value();
        Object vecObj = operands.get(2).value();

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
        Deque<Object> ds = organism.getDataStack();
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

        // The first popped element (X value) goes to index 0.
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