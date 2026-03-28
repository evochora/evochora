package org.evochora.datapipeline.resources.database;

import org.evochora.datapipeline.api.contracts.OrganismRuntimeState;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.database.dto.*;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MoleculeTypeRegistry;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for converting Protobuf organism state data to DTOs.
 * <p>
 * This class provides database-agnostic conversion logic that can be used
 * by any database reader implementation. It handles:
 * <ul>
 *   <li>Protobuf deserialization (Vector, DataPointerList, OrganismRuntimeState)</li>
 *   <li>Protobuf to DTO conversion (RegisterValue, ProcFrame)</li>
 *   <li>Instruction resolution (opcode name, argument types, resolved arguments)</li>
 *   <li>Register value resolution (by register ID)</li>
 * </ul>
 * <p>
 * <strong>Thread Safety:</strong> All methods are stateless and thread-safe.
 */
public final class OrganismStateConverter {
    
    /**
     * Guard to ensure that the instruction set is initialized exactly once per JVM.
     * <p>
     * <strong>Rationale:</strong> When the simulation engine is not running, the
     * Instruction registry may never be initialized. In that case, calls to
     * {@link Instruction#getInstructionNameById(int)} would always return
     * {@code "UNKNOWN"}. This affects the environment visualizer when it reads
     * historical environment data via database readers without having
     * started the simulation engine in the same process.
     * <p>
     * By lazily initializing the instruction set here, we ensure that opcode
     * names are available regardless of whether the simulation engine has been
     * constructed in the current JVM.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean INSTRUCTION_INITIALIZED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    
    private OrganismStateConverter() {
        throw new IllegalStateException("Utility class");
    }
    
    /**
     * Ensures that the instruction set is initialized.
     * <p>
     * This method is idempotent and thread-safe.
     */
    private static void ensureInstructionSetInitialized() {
        if (INSTRUCTION_INITIALIZED.compareAndSet(false, true)) {
            Instruction.init();
        }
    }
    
    /**
     * Decodes a Protobuf Vector from bytes.
     *
     * @param bytes The serialized Vector bytes
     * @return Decoded vector as int array, or null if bytes is null
     * @throws SQLException if deserialization fails
     */
    public static int[] decodeVector(byte[] bytes) throws SQLException {
        if (bytes == null) {
            return null;
        }
        try {
            Vector vec = Vector.parseFrom(bytes);
            int[] result = new int[vec.getComponentsCount()];
            for (int i = 0; i < result.length; i++) {
                result[i] = vec.getComponents(i);
            }
            return result;
        } catch (Exception e) {
            throw new SQLException("Failed to decode vector from bytes", e);
        }
    }
    
    /**
     * Decodes a Protobuf DataPointerList from bytes.
     *
     * @param bytes The serialized DataPointerList bytes
     * @return Decoded data pointers as int[][], or empty array if bytes is null
     * @throws SQLException if deserialization fails
     */
    public static int[][] decodeDataPointers(byte[] bytes) throws SQLException {
        if (bytes == null) {
            return new int[0][];
        }
        try {
            org.evochora.datapipeline.api.contracts.DataPointerList list =
                    org.evochora.datapipeline.api.contracts.DataPointerList.parseFrom(bytes);
            int[][] result = new int[list.getDataPointersCount()][];
            for (int i = 0; i < list.getDataPointersCount(); i++) {
                Vector v = list.getDataPointers(i);
                int[] components = new int[v.getComponentsCount()];
                for (int j = 0; j < components.length; j++) {
                    components[j] = v.getComponents(j);
                }
                result[i] = components;
            }
            return result;
        } catch (Exception e) {
            throw new SQLException("Failed to decode data pointers from bytes", e);
        }
    }
    
    /**
     * Converts a Protobuf Vector to a Java int array.
     *
     * @param v The Protobuf Vector
     * @return Java int array with vector components
     */
    public static int[] vectorToArray(Vector v) {
        int[] result = new int[v.getComponentsCount()];
        for (int i = 0; i < result.length; i++) {
            result[i] = v.getComponents(i);
        }
        return result;
    }
    
    /**
     * Converts a Protobuf RegisterValue to a RegisterValueView DTO.
     *
     * @param rv The Protobuf RegisterValue
     * @return RegisterValueView DTO
     * @throws IllegalStateException if RegisterValue has neither scalar nor vector (violates oneof constraint)
     */
    public static RegisterValueView convertRegisterValue(
            org.evochora.datapipeline.api.contracts.RegisterValue rv) {
        if (rv.hasScalar()) {
            int raw = rv.getScalar();
            Molecule molecule = Molecule.fromInt(raw);
            int typeId = molecule.type();
            String typeName = MoleculeTypeRegistry.typeToName(typeId);
            int value = molecule.toScalarValue();
            return RegisterValueView.molecule(raw, typeId, typeName, value);
        }
        if (rv.hasVector()) {
            return RegisterValueView.vector(vectorToArray(rv.getVector()));
        }
        // This should never happen: RegisterValue is a oneof, so it must have either scalar or vector
        throw new IllegalStateException(
            "RegisterValue has neither scalar nor vector set. " +
            "This violates the Protobuf oneof constraint and indicates corrupted data.");
    }
    
    /**
     * Converts a Protobuf ProcFrame to a ProcFrameView DTO.
     *
     * @param frame The Protobuf ProcFrame
     * @return ProcFrameView DTO
     */
    public static ProcFrameView convertProcFrame(
            org.evochora.datapipeline.api.contracts.ProcFrame frame) {
        String procName = frame.getProcName();
        int[] absReturnIp = vectorToArray(frame.getAbsoluteReturnIp());
        int[] absCallIp = frame.hasAbsoluteCallIp() ? vectorToArray(frame.getAbsoluteCallIp()) : null;

        List<RegisterValueView> savedRegisters = new ArrayList<>(frame.getSavedRegistersCount());
        for (org.evochora.datapipeline.api.contracts.RegisterValue rv : frame.getSavedRegistersList()) {
            savedRegisters.add(convertRegisterValue(rv));
        }

        Map<Integer, Integer> parameterBindings = new HashMap<>(frame.getParameterBindingsMap());

        return new ProcFrameView(procName, absReturnIp, absCallIp, savedRegisters, parameterBindings);
    }
    
    /**
     * Resolves a register value by register ID from a flat register list.
     *
     * @param registerId Register ID (dispatched via RegisterBank lookup)
     * @param registers  Flat register list in RegisterBank slot order
     * @return RegisterValueView
     * @throws IllegalStateException if register ID is invalid or out of bounds
     */
    public static RegisterValueView resolveRegisterValue(
            int registerId,
            List<RegisterValueView> registers) {

        RegisterBank bank = RegisterBank.forId(registerId);
        if (bank == null) {
            throw new IllegalStateException(
                String.format("Invalid register ID %d. No RegisterBank found for this ID.", registerId));
        }

        int slot = RegisterBank.ID_TO_SLOT[registerId];
        if (slot < 0 || slot >= registers.size()) {
            int index = registerId - bank.base;
            throw new IllegalStateException(
                String.format("%s register ID %d (index %d) is out of bounds. " +
                    "Slot %d exceeds available registers (%d).",
                    bank.name(), registerId, index, slot, registers.size()));
        }

        return registers.get(slot);
    }
    
    /**
     * Resolves instruction execution data into an InstructionView.
     *
     * @param opcodeId         Opcode ID of the instruction
     * @param rawArguments      Raw argument values from instruction_raw_arguments
     * @param energyCost        Energy cost of the instruction
     * @param entropyDelta      Entropy delta of the instruction
     * @param ipBeforeFetch     IP before instruction fetch
     * @param dvBeforeFetch     DV before instruction fetch
     * @param failed            Whether the instruction failed
     * @param failureReason     Failure reason (if failed)
     * @param registers         Flat register list in RegisterBank slot order
     * @param envDimensions     Environment dimensions for VECTOR/LABEL arguments
     * @param registerValuesBefore Register values before execution (for annotation display)
     * @return InstructionView
     * @throws IllegalStateException if instruction has no signature registered
     */
    public static InstructionView resolveInstructionView(
            int opcodeId,
            List<Integer> rawArguments,
            int energyCost,
            int entropyDelta,
            int[] ipBeforeFetch,
            int[] dvBeforeFetch,
            boolean failed,
            String failureReason,
            List<RegisterValueView> registers,
            int[] envDimensions,
            java.util.Map<Integer, RegisterValueView> registerValuesBefore) {
        
        ensureInstructionSetInitialized();
        
        // Resolve opcode name
        String opcodeName = Instruction.getInstructionNameById(opcodeId);
        
        // Get signature for argument types
        java.util.Optional<org.evochora.runtime.isa.InstructionSignature> signatureOpt =
                Instruction.getSignatureById(opcodeId);
        
        // Handle unknown opcodes (e.g., from self-modifying code where a non-instruction molecule was executed)
        // This matches the runtime's behavior: VirtualMachine.plan() creates a NopInstruction with name "UNKNOWN"
        // when an unknown opcode is encountered, and marks instructionFailed=true.
        if (signatureOpt.isEmpty()) {
            // Return a fallback InstructionView for unknown opcodes
            // No arguments can be resolved without a signature, but we still preserve the execution metadata
            // The runtime always marks instructionFailed=true for unknown opcodes, so we ensure failed=true here too
            
            // Extract molecule type and value to show what was actually executed
            Molecule molecule = Molecule.fromInt(opcodeId);
            String moleculeTypeName = MoleculeTypeRegistry.typeToName(molecule.type());
            int moleculeValue = molecule.toScalarValue();
            String formattedOpcodeName = String.format("UNKNOWN [%s:%d]", moleculeTypeName, moleculeValue);
            
            String unknownReason = failureReason != null && !failureReason.isEmpty() 
                    ? failureReason 
                    : "Unknown opcode: " + opcodeId + " (" + moleculeTypeName + ":" + moleculeValue + ")";
            return new InstructionView(
                    opcodeId,
                    formattedOpcodeName, // e.g., "UNKNOWN [DATA:42]" or "UNKNOWN [STRUCTURE:10]"
                    java.util.Collections.emptyList(), // No arguments for unknown instructions
                    java.util.Collections.emptyList(), // No argument types without signature
                    energyCost,
                    entropyDelta,
                    ipBeforeFetch != null ? ipBeforeFetch : new int[0],
                    dvBeforeFetch != null ? dvBeforeFetch : new int[0],
                    true, // Always true for unknown opcodes (runtime sets instructionFailed=true)
                    unknownReason
            );
        }
        
        org.evochora.runtime.isa.InstructionSignature signature = signatureOpt.get();
        List<org.evochora.runtime.isa.InstructionArgumentType> argTypes = signature.argumentTypes();
        
        // Build argument types list as strings from signature (STACK is not in signature, as it's not in code)
        List<String> argumentTypesList = new ArrayList<>();
        List<InstructionArgumentView> resolvedArgs = new ArrayList<>();
        int argIndex = 0;
        
        // Parse rawArguments using signature (only arguments that are actually in the code)
        for (org.evochora.runtime.isa.InstructionArgumentType argType : argTypes) {
            if (argType == org.evochora.runtime.isa.InstructionArgumentType.REGISTER) {
                // REGISTER: Extract register ID from raw argument
                argumentTypesList.add("REGISTER");
                if (argIndex < rawArguments.size()) {
                    int rawArg = rawArguments.get(argIndex);
                    Molecule molecule = Molecule.fromInt(rawArg);
                    int registerId = molecule.toScalarValue();
                    
                    // Determine register type via RegisterBank lookup
                    RegisterBank regBank = RegisterBank.forId(registerId);
                    String registerType = regBank != null ? regBank.name() : "UNKNOWN";
                    
                    // Resolve register value: use value BEFORE execution (null if unavailable)
                    RegisterValueView registerValue = (registerValuesBefore != null)
                        ? registerValuesBefore.get(registerId)
                        : null;
                    
                    resolvedArgs.add(InstructionArgumentView.register(registerId, registerValue, registerType));
                    argIndex++;
                }
            } else if (argType == org.evochora.runtime.isa.InstructionArgumentType.LOCATION_REGISTER) {
                // LOCATION_REGISTER: Extract register ID from raw argument
                argumentTypesList.add("REGISTER"); // Frontend zeigt als REGISTER mit registerType="LR"
                if (argIndex >= rawArguments.size()) {
                    throw new IllegalStateException(
                        String.format("LOCATION_REGISTER argument missing for instruction %d (%s). " +
                            "Expected %d arguments but only %d available in rawArguments.",
                            opcodeId, opcodeName, argTypes.size(), rawArguments.size()));
                }
                
                int rawArg = rawArguments.get(argIndex);
                Molecule molecule = Molecule.fromInt(rawArg);
                int registerId = molecule.toScalarValue();
                
                // Determine register type via RegisterBank lookup
                RegisterBank locBank = RegisterBank.forId(registerId);
                String registerType = locBank != null ? locBank.name() : "UNKNOWN";

                // Resolve register value: use value BEFORE execution (same as REGISTER path)
                RegisterValueView registerValue = (registerValuesBefore != null)
                    ? registerValuesBefore.get(registerId)
                    : null;

                int index = locBank != null ? registerId - locBank.base : registerId;
                resolvedArgs.add(InstructionArgumentView.register(index, registerValue, registerType));
                argIndex++;
            } else if (argType == org.evochora.runtime.isa.InstructionArgumentType.LITERAL) {
                // LITERAL: Decode molecule type and value (shown as IMMEDIATE in view)
                argumentTypesList.add("IMMEDIATE");
                if (argIndex < rawArguments.size()) {
                    int rawArg = rawArguments.get(argIndex);
                    Molecule molecule = Molecule.fromInt(rawArg);
                    int typeId = molecule.type();
                    String moleculeType = MoleculeTypeRegistry.typeToName(typeId);
                    int value = molecule.toScalarValue();
                    
                    resolvedArgs.add(InstructionArgumentView.immediate(rawArg, moleculeType, value));
                    argIndex++;
                }
            } else if (argType == org.evochora.runtime.isa.InstructionArgumentType.VECTOR) {
                // VECTOR: Group multiple arguments into int[] array
                argumentTypesList.add("VECTOR");
                int dims = envDimensions != null ? envDimensions.length : 2;
                int[] components = new int[dims];
                boolean hasComponents = false;
                
                for (int dim = 0; dim < dims && argIndex < rawArguments.size(); dim++) {
                    int rawArg = rawArguments.get(argIndex);
                    Molecule molecule = Molecule.fromInt(rawArg);
                    components[dim] = molecule.toScalarValue();
                    hasComponents = true;
                    argIndex++;
                }
                
                if (hasComponents) {
                    resolvedArgs.add(InstructionArgumentView.vector(components));
                }
            } else if (argType == org.evochora.runtime.isa.InstructionArgumentType.LABEL) {
                // LABEL: Single scalar hash value (20-bit) since fuzzy jumps refactoring
                // Formatted like IMMEDIATE with molecule type (typically DATA)
                argumentTypesList.add("LABEL");
                if (argIndex < rawArguments.size()) {
                    int rawArg = rawArguments.get(argIndex);
                    Molecule molecule = Molecule.fromInt(rawArg);
                    int typeId = molecule.type();
                    String moleculeType = MoleculeTypeRegistry.typeToName(typeId);
                    int hashValue = molecule.toScalarValue();
                    resolvedArgs.add(InstructionArgumentView.label(rawArg, moleculeType, hashValue));
                    argIndex++;
                }
            }
        }
        
        return new InstructionView(
                opcodeId,
                opcodeName,
                resolvedArgs,
                argumentTypesList,
                energyCost,
                entropyDelta,
                ipBeforeFetch != null ? ipBeforeFetch : new int[0],
                dvBeforeFetch != null ? dvBeforeFetch : new int[0],
                failed,
                failureReason
        );
    }
    
    /**
     * Decodes an OrganismRuntimeState from a compressed blob and converts it to an OrganismRuntimeView DTO.
     *
     * @param energy         Energy level
     * @param ip             Instruction pointer
     * @param dv             Direction vector
     * @param dataPointers   Data pointers
     * @param activeDpIndex  Active data pointer index
     * @param blobBytes      Compressed Protobuf blob
     * @param envDimensions  Environment dimensions for instruction resolution (can be null)
     * @return OrganismRuntimeView DTO
     * @throws SQLException if deserialization fails
     * @throws IllegalStateException if instruction data is corrupted (opcodeId without energyCost)
     */
    public static OrganismRuntimeView decodeRuntimeState(int energy,
                                                         int[] ip,
                                                         int[] dv,
                                                         int[][] dataPointers,
                                                         int activeDpIndex,
                                                         byte[] blobBytes,
                                                         int[] envDimensions) throws SQLException {
        if (blobBytes == null) {
            throw new SQLException("runtime_state_blob is null");
        }

        org.evochora.datapipeline.utils.compression.ICompressionCodec codec =
                org.evochora.datapipeline.utils.compression.CompressionCodecFactory
                        .detectFromMagicBytes(blobBytes);

        OrganismRuntimeState state;
        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(blobBytes);
             java.io.InputStream in = codec.wrapInputStream(bais)) {
            state = OrganismRuntimeState.parseFrom(in);
        } catch (Exception e) {
            throw new SQLException("Failed to decode OrganismRuntimeState", e);
        }

        List<RegisterValueView> registers = new ArrayList<>(state.getRegistersCount());
        for (org.evochora.datapipeline.api.contracts.RegisterValue rv : state.getRegistersList()) {
            registers.add(convertRegisterValue(rv));
        }

        List<RegisterValueView> dataStack = new ArrayList<>(state.getDataStackCount());
        for (org.evochora.datapipeline.api.contracts.RegisterValue rv : state.getDataStackList()) {
            dataStack.add(convertRegisterValue(rv));
        }

        List<int[]> locationStack = new ArrayList<>(state.getLocationStackCount());
        for (Vector v : state.getLocationStackList()) {
            locationStack.add(vectorToArray(v));
        }

        List<ProcFrameView> callStack = new ArrayList<>(state.getCallStackCount());
        for (org.evochora.datapipeline.api.contracts.ProcFrame frame : state.getCallStackList()) {
            callStack.add(convertProcFrame(frame));
        }

        List<ProcFrameView> failureStack = new ArrayList<>(state.getFailureCallStackCount());
        for (org.evochora.datapipeline.api.contracts.ProcFrame frame : state.getFailureCallStackList()) {
            failureStack.add(convertProcFrame(frame));
        }

        // Resolve instructions
        InstructionView lastInstruction = null;
        if (state.hasInstructionOpcodeId() && envDimensions != null) {
            // If opcodeId is present, energyCost must also be present (they are set together)
            if (!state.hasInstructionEnergyCost()) {
                throw new IllegalStateException(
                    "Instruction execution data has opcode ID but no energy cost. " +
                    "This indicates corrupted data - all executed instructions must have an energy cost.");
            }
            
            // Read register values before execution from Protobuf
            java.util.Map<Integer, RegisterValueView> registerValuesBefore = new java.util.HashMap<>();
            if (state.getInstructionRegisterValuesBeforeCount() > 0) {
                for (java.util.Map.Entry<Integer, org.evochora.datapipeline.api.contracts.RegisterValue> entry :
                        state.getInstructionRegisterValuesBeforeMap().entrySet()) {
                    int registerId = entry.getKey();
                    RegisterValueView registerValue = convertRegisterValue(entry.getValue());
                    registerValuesBefore.put(registerId, registerValue);
                }
            }
            
            lastInstruction = resolveInstructionView(
                    state.getInstructionOpcodeId(),
                    state.getInstructionRawArgumentsList(),
                    state.getInstructionEnergyCost(),
                    state.hasInstructionEntropyDelta() ? state.getInstructionEntropyDelta() : 0,
                    state.hasInstructionIpBeforeFetch() ? vectorToArray(state.getInstructionIpBeforeFetch()) : null,
                    state.hasInstructionDvBeforeFetch() ? vectorToArray(state.getInstructionDvBeforeFetch()) : null,
                    state.getInstructionFailed(),
                    state.getFailureReason().isEmpty() ? null : state.getFailureReason(),
                    registers,
                    envDimensions,
                    registerValuesBefore
            );
        }
        InstructionsView instructions = new InstructionsView(lastInstruction, null);

        return new OrganismRuntimeView(
                energy,
                ip,
                dv,
                dataPointers,
                activeDpIndex,
                registers,
                dataStack,
                locationStack,
                callStack,
                state.getInstructionFailed(),
                state.getFailureReason(),
                failureStack,
                instructions,
                state.getEntropyRegister(),
                state.getMoleculeMarkerRegister()
        );
    }
}

