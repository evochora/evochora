package org.evochora.runtime.internal.services;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

import java.util.HashMap;
import java.util.Map;

public class ProcedureCallHandler {

    private final ExecutionContext context;

    public ProcedureCallHandler(ExecutionContext context) {
        this.context = context;
    }

    public void executeCall(int[] targetDelta, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();

        if (organism.getCallStack().size() >= Config.CALL_STACK_MAX_DEPTH) {
            organism.instructionFailed("Call stack overflow");
            return;
        }

        CallBindingResolver bindingResolver = new CallBindingResolver(context);
        int[] bindings = bindingResolver.resolveBindings();
        int[] ipBeforeFetch = organism.getIpBeforeFetch();

        Map<Integer, Integer> fprBindings = new HashMap<>();
        if (bindings != null) {
            for (int i = 0; i < bindings.length; i++) {
                if (i < organism.getFprs().size()) {
                    fprBindings.put(Instruction.FPR_BASE + i, bindings[i]);
                }
            }
        }

        int instructionLength = 1 + environment.getShape().length;
        int[] returnIp = ipBeforeFetch;
        for (int i = 0; i < instructionLength; i++) {
            returnIp = organism.getNextInstructionPosition(returnIp, organism.getDvBeforeFetch(), environment); // CORRECTED
        }

        Object[] prsSnapshot = organism.getPrs().toArray();
        Object[] fprsSnapshot = organism.getFprs().toArray();

        String procName = "UNKNOWN";

        int[] targetIp = organism.getTargetCoordinate(organism.getInitialPosition(), targetDelta, environment);

        if (artifact != null) {
            int[] origin = organism.getInitialPosition();
            int[] relTarget = new int[targetDelta.length];
            for (int i = 0; i < targetDelta.length; i++) {
                relTarget[i] = targetIp[i] - origin[i];
            }

            StringBuilder keyBuilder = new StringBuilder();
            for (int i = 0; i < relTarget.length; i++) {
                if (i > 0) keyBuilder.append('|');
                keyBuilder.append(relTarget[i]);
            }
            String relativeKey = keyBuilder.toString();
            Integer targetAddress = artifact.relativeCoordToLinearAddress().get(relativeKey);
            if (targetAddress != null) {
                String name = artifact.labelAddressToName().get(targetAddress);
                if (name != null) procName = name;
            }
        }

        Organism.ProcFrame frame = new Organism.ProcFrame(procName, returnIp, prsSnapshot, fprsSnapshot, fprBindings);
        organism.getCallStack().push(frame);

        if (bindings != null) {
            for (int i = 0; i < bindings.length; i++) {
                Object value = organism.readOperand(bindings[i]);
                organism.setFpr(i, value);
            }
        }

        organism.setIp(targetIp);
        organism.setSkipIpAdvance(true);
    }

    public void executeReturn() {
        Organism organism = context.getOrganism();

        if (organism.getCallStack().isEmpty()) {
            organism.instructionFailed("Call stack underflow (RET without CALL)");
            return;
        }
        Organism.ProcFrame returnFrame = organism.getCallStack().pop();

        organism.restorePrs(returnFrame.savedPrs);
        organism.setIp(returnFrame.absoluteReturnIp);
        organism.setSkipIpAdvance(true);
    }
}