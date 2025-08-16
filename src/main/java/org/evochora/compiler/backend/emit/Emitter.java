package org.evochora.compiler.backend.emit;

import org.evochora.runtime.Config;
import org.evochora.compiler.api.PlacedMolecule;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.ir.*;
import org.evochora.runtime.model.Molecule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Emitter {

    // --- START DER ÄNDERUNG ---
    public ProgramArtifact emit(IrProgram program,
                                LayoutResult layout,
                                LinkingContext linkingContext,
                                IInstructionSet isa,
                                Map<String, Integer> registerAliasMap) { // Neuer Parameter
        // --- ENDE DER ÄNDERUNG ---
        Map<int[], Integer> machineCodeLayout = new HashMap<>();
        Map<Integer, int[]> linearToCoord = layout.linearAddressToCoord();
        Map<List<Integer>, Integer> coordToLinear = layout.relativeCoordToLinearAddress();
        Map<Integer, SourceInfo> sourceMap = layout.sourceMap();
        Map<int[], PlacedMolecule> initialObjects = layout.initialWorldObjects();

        int address = 0;
        for (IrItem item : program.items()) {
            if (item instanceof IrInstruction ins) {
                int opcode = isa.getInstructionIdByName(ins.opcode()).orElseThrow(() ->
                        new IllegalArgumentException("Unknown opcode: " + ins.opcode()));
                int[] opcodeCoord = linearToCoord.get(address);
                if (opcodeCoord == null) throw new IllegalStateException("Missing coord for address " + address);
                machineCodeLayout.put(opcodeCoord, opcode);
                address++;

                List<IrOperand> ops = ins.operands();
                if (ops != null) {
                    for (IrOperand op : ops) {
                        if (op instanceof IrVec vec) {
                            int[] comps = vec.components();
                            for (int c : comps) {
                                int[] coord = linearToCoord.get(address);
                                if (coord == null) throw new IllegalStateException("Missing coord for address " + address);
                                machineCodeLayout.put(coord, new Molecule(Config.TYPE_DATA, c).toInt());
                                address++;
                            }
                        } else {
                            int[] coord = linearToCoord.get(address);
                            if (coord == null) throw new IllegalStateException("Missing coord for address " + address);
                            Integer value = encodeOperand(op, isa);
                            machineCodeLayout.put(coord, value);
                            address++;
                        }
                    }
                }
            }
        }

        Map<Integer, String> labelAddressToName = new HashMap<>();
        layout.labelToAddress().forEach((name, addr) -> labelAddressToName.put(addr, name));

        String programId = Integer.toHexString(machineCodeLayout.hashCode());

        return new ProgramArtifact(
                programId,
                machineCodeLayout,
                initialObjects,
                sourceMap,
                linkingContext.callSiteBindings(),
                coordToLinear,
                linearToCoord,
                labelAddressToName,
                registerAliasMap // <-- HINZUGEFÜGT
        );
    }

    private Integer encodeOperand(IrOperand op, IInstructionSet isa) {
        if (op instanceof IrReg r) {
            int regId = isa.resolveRegisterToken(r.name()).orElseThrow(() -> new IllegalArgumentException("Unknown register: " + r.name()));
            return new Molecule(Config.TYPE_DATA, regId).toInt();
        }
        if (op instanceof IrImm imm) {
            return new Molecule(Config.TYPE_DATA, (int) imm.value()).toInt();
        }
        if (op instanceof IrTypedImm ti) {
            int type = switch (ti.typeName().toUpperCase()) {
                case "CODE" -> Config.TYPE_CODE;
                case "ENERGY" -> Config.TYPE_ENERGY;
                case "STRUCTURE" -> Config.TYPE_STRUCTURE;
                default -> Config.TYPE_DATA;
            };
            return new Molecule(type, (int) ti.value()).toInt();
        }
        if (op instanceof IrVec vec) {
            return new Molecule(Config.TYPE_DATA, vec.components()[0]).toInt();
        }
        throw new IllegalArgumentException("Unsupported operand type: " + op.getClass().getSimpleName());
    }
}