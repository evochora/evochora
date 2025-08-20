package org.evochora.runtime.isa.instructions;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.util.Deque;
import java.util.List;

public class LocationInstruction extends Instruction {

    public LocationInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context, ProgramArtifact artifact) {
        Organism org = context.getOrganism();
        Environment env = context.getWorld();
        String name = getName();
        List<Operand> ops = resolveOperands(env);

        Deque<int[]> ls = org.getLocationStack();

        switch (name) {
            case "DUPL": {
                if (ls.isEmpty()) { org.instructionFailed("DUPL on empty LS"); return; }
                ls.push(ls.peek());
                break;
            }
            case "SWPL": {
                if (ls.size() < 2) { org.instructionFailed("SWPL requires 2 elements on LS"); return; }
                int[] a = ls.pop();
                int[] b = ls.pop();
                ls.push(a); ls.push(b);
                break;
            }
            case "DRPL": {
                if (ls.isEmpty()) { org.instructionFailed("DRPL on empty LS"); return; }
                ls.pop();
                break;
            }
            case "ROTL": {
                if (ls.size() < 3) { org.instructionFailed("ROTL requires 3 elements on LS"); return; }
                int[] a = ls.pop();
                int[] b = ls.pop();
                int[] c = ls.pop();
                ls.push(b); ls.push(a); ls.push(c);
                break;
            }
            case "DPLR": {
                if (ops.size() != 1) { org.instructionFailed("DPLR expects <LR_Index>"); return; }
                int lrIdx = Molecule.fromInt((Integer) ops.get(0).value()).toScalarValue();
                org.setLr(lrIdx, org.getActiveDp());
                break;
            }
            case "DPLS": {
                ls.push(org.getActiveDp());
                break;
            }
            case "SKLR": {
                if (ops.size() != 1) { org.instructionFailed("SKLR expects <LR_Index>"); return; }
                int lrIdx = Molecule.fromInt((Integer) ops.get(0).value()).toScalarValue();
                int[] target = org.getLr(lrIdx);
                if (target == null) { org.instructionFailed("Invalid LR index"); return; }
                org.setActiveDp(target);
                break;
            }
            case "SKLS": {
                if (ls.isEmpty()) { org.instructionFailed("SKLS on empty LS"); return; }
                int[] target = ls.pop();
                org.setActiveDp(target);
                break;
            }
            case "PUSL": {
                if (ops.size() != 1) { org.instructionFailed("PUSL expects <LR_Index>"); return; }
                int lrIdx = Molecule.fromInt((Integer) ops.get(0).value()).toScalarValue();
                int[] vec = org.getLr(lrIdx);
                if (vec == null) { org.instructionFailed("Invalid LR index"); return; }
                org.getLocationStack().push(vec);
                break;
            }
            case "POPL": {
                if (ops.size() != 1) { org.instructionFailed("POPL expects <LR_Index>"); return; }
                if (ls.isEmpty()) { org.instructionFailed("POPL on empty LS"); return; }
                int lrIdx = Molecule.fromInt((Integer) ops.get(0).value()).toScalarValue();
                int[] vec = ls.pop();
                org.setLr(lrIdx, vec);
                break;
            }
            case "LRDR": {
                if (ops.size() != 2) { org.instructionFailed("LRDR expects <Dest_Reg>, <LR_Index>"); return; }
                int destReg = ops.get(0).rawSourceId();
                int lrIdx = Molecule.fromInt((Integer) ops.get(1).value()).toScalarValue();
                int[] vec = org.getLr(lrIdx);
                if (vec == null) { org.instructionFailed("Invalid LR index"); return; }
                writeOperand(destReg, vec);
                break;
            }
            case "LRDS": {
                if (ops.size() != 1) { org.instructionFailed("LRDS expects <LR_Index>"); return; }
                int lrIdx = Molecule.fromInt((Integer) ops.get(0).value()).toScalarValue();
                int[] vec = org.getLr(lrIdx);
                if (vec == null) { org.instructionFailed("Invalid LR index"); return; }
                org.getDataStack().push(vec);
                break;
            }
            case "LSDR": {
                if (ops.size() != 1) { org.instructionFailed("LSDR expects <Dest_Reg>"); return; }
                int destReg = ops.get(0).rawSourceId();
                if (ls.isEmpty()) { org.instructionFailed("LSDR on empty LS"); return; }
                int[] vec = ls.peek();
                writeOperand(destReg, vec);
                break;
            }
            case "LSDS": {
                if (ls.isEmpty()) { org.instructionFailed("LSDS on empty LS"); return; }
                int[] vec = ls.pop();
                org.getDataStack().push(vec);
                break;
            }
            default:
                org.instructionFailed("Unknown location instruction: " + name);
        }
    }
}

