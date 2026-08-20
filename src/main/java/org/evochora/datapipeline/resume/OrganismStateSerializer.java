package org.evochora.datapipeline.resume;

import java.util.Map;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PersistentRegisterStore;
import org.evochora.datapipeline.api.contracts.ProcedureRegisterSnapshot;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Organism.ProcFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serializes an {@link Organism} into its {@link OrganismState} message — the counterpart of
 * {@link SimulationRestorer}, which rebuilds organisms from that message.
 * <p>
 * Serialization is an observer of the simulation: it never modifies the organism, because state
 * is only captured on sampled ticks and any write would make the run depend on how often it is
 * observed. Corrupt values are reported and substituted instead of aborting a long-running run.
 * <p>
 * The Protobuf builders are reused across calls to avoid allocations per organism, so an instance
 * is <b>not thread-safe</b>; the engine owns one instance and calls it from its own thread.
 */
public final class OrganismStateSerializer {

    private static final Logger log = LoggerFactory.getLogger(OrganismStateSerializer.class);

    private final OrganismState.Builder organismStateBuilder = OrganismState.newBuilder();
    private final Vector.Builder vectorBuilder = Vector.newBuilder();
    private final org.evochora.datapipeline.api.contracts.RegisterValue.Builder registerValueBuilder =
            org.evochora.datapipeline.api.contracts.RegisterValue.newBuilder();
    private final org.evochora.datapipeline.api.contracts.ProcFrame.Builder procFrameBuilder =
            org.evochora.datapipeline.api.contracts.ProcFrame.newBuilder();


    /**
     * Serializes the complete state of an organism, including the execution data of its last
     * instruction and a preview of its next one.
     *
     * @param o the organism to serialize
     * @return the organism's state message
     */
    public OrganismState serialize(Organism o) {
        organismStateBuilder.clear();
        vectorBuilder.clear();
        registerValueBuilder.clear();

        organismStateBuilder.setOrganismId(o.getId());
        if (o.getParentId() != null) organismStateBuilder.setParentId(o.getParentId());
        organismStateBuilder.setBirthTick(o.getBirthTick());
        organismStateBuilder.setProgramId(o.getProgramId());
        organismStateBuilder.setEnergy(o.getEr());

        organismStateBuilder.setIp(convertVectorReuse(o.getIp(), vectorBuilder));
        organismStateBuilder.setInitialPosition(convertVectorReuse(o.getInitialPosition(), vectorBuilder));
        organismStateBuilder.setDv(convertVectorReuse(o.getDv(), vectorBuilder));

        for (int[] dp : o.getDps()) {
            organismStateBuilder.addDataPointers(convertVectorReuse(dp, vectorBuilder));
        }
        organismStateBuilder.setActiveDpIndex(o.getActiveDpIndex());

        Object[] registers = o.getRegisters();
        for (int slot = 0; slot < registers.length; slot++) {
            Object rv = registers[slot];
            if (rv == null) {
                rv = defaultForRegisterSlot(slot, o);
            }
            organismStateBuilder.addRegisters(convertRegisterValueReuse(rv, registerValueBuilder, vectorBuilder));
        }
        for (Object rv : o.getDataStack()) {
            organismStateBuilder.addDataStack(convertRegisterValueReuse(rv, registerValueBuilder, vectorBuilder));
        }
        for (int[] loc : o.getLocationStack()) {
            organismStateBuilder.addLocationStack(convertVectorReuse(loc, vectorBuilder));
        }
        for (ProcFrame frame : o.getCallStack()) {
            organismStateBuilder.addCallStack(convertProcFrameReuse(frame));
        }

        organismStateBuilder.setIsDead(o.isDead());
        organismStateBuilder.setInstructionFailed(o.isInstructionFailed());
        if (o.getFailureReason() != null) organismStateBuilder.setFailureReason(o.getFailureReason());
        if (o.getFailureCallStack() != null) {
            for (ProcFrame frame : o.getFailureCallStack()) {
                organismStateBuilder.addFailureCallStack(convertProcFrameReuse(frame));
            }
        }

        // Instruction execution data
        Organism.InstructionExecutionData executionData = o.getLastInstructionExecution();
        if (executionData != null) {
            organismStateBuilder.setInstructionOpcodeId(executionData.opcodeId());
            for (int arg : executionData.rawArguments()) {
                organismStateBuilder.addInstructionRawArguments(arg);
            }
            organismStateBuilder.setInstructionEnergyCost(executionData.energyCost());
            organismStateBuilder.setInstructionEntropyDelta(executionData.entropyDelta());

            // Register values before execution (for annotation display)
            if (executionData.registerValuesBefore() != null && !executionData.registerValuesBefore().isEmpty()) {
                for (java.util.Map.Entry<Integer, Object> entry : executionData.registerValuesBefore().entrySet()) {
                    int registerId = entry.getKey();
                    Object registerValue = entry.getValue();
                    org.evochora.datapipeline.api.contracts.RegisterValue protoValue =
                        convertRegisterValueReuse(registerValue, registerValueBuilder, vectorBuilder);
                    organismStateBuilder.putInstructionRegisterValuesBefore(registerId, protoValue);
                }
            }
        }

        // Next instruction preview (peek at molecule at current IP)
        Organism.InstructionExecutionData nextData = o.getSimulation().getVirtualMachine().peekNextInstruction(o);
        if (nextData != null) {
            organismStateBuilder.setNextInstructionOpcodeId(nextData.opcodeId());
            for (int arg : nextData.rawArguments()) {
                organismStateBuilder.addNextInstructionRawArguments(arg);
            }
            if (nextData.registerValuesBefore() != null && !nextData.registerValuesBefore().isEmpty()) {
                for (java.util.Map.Entry<Integer, Object> entry : nextData.registerValuesBefore().entrySet()) {
                    org.evochora.datapipeline.api.contracts.RegisterValue protoValue =
                        convertRegisterValueReuse(entry.getValue(), registerValueBuilder, vectorBuilder);
                    organismStateBuilder.putNextInstructionRegisterValuesBefore(entry.getKey(), protoValue);
                }
            }
        }

        // IP and DV before fetch
        organismStateBuilder.setIpBeforeFetch(convertVectorReuse(o.getIpBeforeFetch(), vectorBuilder));
        organismStateBuilder.setDvBeforeFetch(convertVectorReuse(o.getDvBeforeFetch(), vectorBuilder));

        // Special registers
        organismStateBuilder.setEntropyRegister(o.getSr());
        organismStateBuilder.setMoleculeMarkerRegister(o.getMr());
        organismStateBuilder.setGenomeHash(o.getGenomeHash());
        if (o.getDeathTick() >= 0) {
            organismStateBuilder.setDeathTick(o.getDeathTick());
        }

        // Persistent register state + dirty flags
        organismStateBuilder.setCurrentProcLabelHash(o.getCurrentProcLabelHash());
        organismStateBuilder.setStackSavedDirty(o.isStackSavedDirty());
        organismStateBuilder.setPersistentDirty(o.isPersistentDirty());
        PersistentRegisterStore.Builder storeBuilder = PersistentRegisterStore.newBuilder();
        for (Map.Entry<Integer, Object[]> entry : o.getPersistentRegisterState().entrySet()) {
            ProcedureRegisterSnapshot.Builder snapshotBuilder = ProcedureRegisterSnapshot.newBuilder()
                    .setLabelHash(entry.getKey());
            for (Object rv : entry.getValue()) {
                snapshotBuilder.addRegisters(convertRegisterValueReuse(rv, registerValueBuilder, vectorBuilder));
            }
            storeBuilder.addProcedureSnapshots(snapshotBuilder.build());
        }
        organismStateBuilder.setPersistentRegisterStore(storeBuilder.build());

        return organismStateBuilder.build();
    }
    
    private static Vector convertVectorReuse(int[] components, Vector.Builder builder) {
        builder.clear();
        if (components != null) {
            for (int c : components) {
                builder.addComponents(c);
            }
        }
        return builder.build();
    }

    /**
     * Returns the type-correct default for a register slot whose value is missing.
     * <p>
     * A register slot is never {@code null} in a well-formed organism — every bank is filled with
     * defaults on construction and on restore. Encountering one means the organism state is corrupt,
     * which is reported here but does not abort the run: serialization is an observer of the
     * simulation, and aborting a long-running simulation over a display value would be worse than
     * emitting a substitute.
     * <p>
     * The organism itself is deliberately left untouched. Writing the default back would set the
     * bank's dirty flag and thereby change CALL/RET snapshot behavior, and because state is only
     * serialized on sampled ticks, the simulation would then depend on how often it is observed.
     * The consequence of not repairing is that the warning repeats for every sampled tick as long as
     * the corrupt slot exists.
     *
     * @param slot flat register array index whose value was missing
     * @param o the organism being serialized
     * @return {@code 0} for data banks, a zero vector for location banks
     */
    private Object defaultForRegisterSlot(int slot, Organism o) {
        RegisterBank bank = RegisterBank.SLOT_TO_BANK[slot];
        log.warn("Null register at slot {} ({}) for organism {} — substituting default for serialization",
                slot, bank != null ? bank.name() : "UNKNOWN", o.getId());
        return bank != null && bank.isLocation ? new int[o.getIp().length] : 0;
    }

    /**
     * Converts a register value into its Protobuf representation, reusing the supplied builders.
     * <p>
     * Values are either {@code Integer} or {@code int[]}. Anything else indicates corrupt organism
     * state; a substitute scalar is emitted rather than aborting the run, for the same reason as in
     * {@link #defaultForRegisterSlot(int, Organism)}. Affected ticks are identifiable in the log, not
     * in the data itself — analyses that must exclude corrupt ticks need the log to do so.
     */
    private org.evochora.datapipeline.api.contracts.RegisterValue convertRegisterValueReuse(
            Object rv, org.evochora.datapipeline.api.contracts.RegisterValue.Builder registerBuilder, Vector.Builder vectorBuilder) {
        registerBuilder.clear();
        if (rv instanceof Integer) {
            registerBuilder.setScalar((Integer) rv);
        } else if (rv instanceof int[]) {
            registerBuilder.setVector(convertVectorReuse((int[]) rv, vectorBuilder));
        } else {
            log.warn("Unexpected register value type during serialization: {} — substituting scalar 0",
                    rv == null ? "null" : rv.getClass().getName());
            registerBuilder.setScalar(0);
        }
        return registerBuilder.build();
    }

    private org.evochora.datapipeline.api.contracts.ProcFrame convertProcFrameReuse(ProcFrame frame) {
        procFrameBuilder.clear();
        procFrameBuilder
                .setProcName(frame.procName())
                .setLabelHash(frame.labelHash())
                .setAbsoluteReturnIp(convertVectorReuse(frame.absoluteReturnIp(), vectorBuilder))
                .setAbsoluteCallIp(convertVectorReuse(frame.absoluteCallIp(), vectorBuilder))
                .putAllParameterBindings(frame.parameterBindings());

        if (frame.savedRegisters() != null) {
            for (Object rv : frame.savedRegisters()) {
                procFrameBuilder.addSavedRegisters(convertRegisterValueReuse(rv, registerValueBuilder, vectorBuilder));
            }
        }

        return procFrameBuilder.build();
    }

}
