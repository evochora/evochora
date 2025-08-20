package org.evochora.runtime.internal.services;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.api.DisassembledArgument;
import org.evochora.runtime.api.DisassembledInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RuntimeDisassembler {

    public static final RuntimeDisassembler INSTANCE = new RuntimeDisassembler();
    private RuntimeDisassembler() {}

    public DisassembledInstruction disassemble(ExecutionContext context, ProgramArtifact artifact) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();
        int[] ip = organism.getIp();
        int[] dv = organism.getDv();
        Molecule molecule = environment.getMolecule(ip);

        int opcodeId = molecule.toInt();
        String opcodeName = Instruction.getInstructionNameById(opcodeId);

        if ("UNKNOWN".equals(opcodeName)) {
            return new DisassembledInstruction("UNKNOWN_OP (" + molecule.value() + ")", List.of(), null, null);
        }

        List<DisassembledArgument> arguments = new ArrayList<>();
        Optional<InstructionSignature> signatureOpt = Instruction.getSignatureById(opcodeId);
        if (signatureOpt.isEmpty()) {
            return new DisassembledInstruction(opcodeName, arguments, null, null);
        }

        InstructionSignature signature = signatureOpt.get();
        int[] currentArgCoord = ip;

        for (InstructionArgumentType argType : signature.argumentTypes()) {
            currentArgCoord = organism.getNextInstructionPosition(currentArgCoord, dv, environment); // CORRECTED
            Molecule argMol = environment.getMolecule(currentArgCoord);
            int argValue = argMol.toScalarValue();
            String argDisplay = argMol.toString();

            switch (argType) {
                case REGISTER: {
                    String argName = resolveRegisterName(argValue, artifact);
                    Object regVal = organism.readOperand(argValue);
                    String fullDisplay = formatRuntimeValue(regVal);
                    arguments.add(new DisassembledArgument("register", argName, argValue, fullDisplay));
                    break;
                }
                case VECTOR: {
                    int[] vec = new int[Config.WORLD_DIMENSIONS];
                    vec[0] = argValue;
                    int[] next = currentArgCoord;
                    for (int i = 1; i < Config.WORLD_DIMENSIONS; i++) {
                        next = organism.getNextInstructionPosition(next, dv, environment); // CORRECTED
                        Molecule m = environment.getMolecule(next);
                        vec[i] = m.toScalarValue();
                    }
                    String fullDisplay = formatRuntimeValue(vec);
                    arguments.add(new DisassembledArgument("vector", fullDisplay, 0, fullDisplay));
                    currentArgCoord = next;
                    break;
                }
                case LABEL: {
                    int[] vec = new int[Config.WORLD_DIMENSIONS];
                    vec[0] = argValue;
                    int[] next = currentArgCoord;
                    for (int i = 1; i < Config.WORLD_DIMENSIONS; i++) {
                        next = organism.getNextInstructionPosition(next, dv, environment); // CORRECTED
                        Molecule m = environment.getMolecule(next);
                        vec[i] = m.toScalarValue();
                    }
                    String fullDisplay = formatRuntimeValue(vec);
                    arguments.add(new DisassembledArgument("vector", fullDisplay, 0, fullDisplay));
                    currentArgCoord = next;
                    break;
                }
                default: {
                    arguments.add(new DisassembledArgument(argType.toString().toLowerCase(), argDisplay, argValue, argDisplay));
                }
            }
        }
        String fileName = null;
        Integer lineNumber = null;
        if (artifact != null && artifact.sourceMap() != null && artifact.relativeCoordToLinearAddress() != null) {
            int[] origin = organism.getInitialPosition();
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < ip.length; i++) {
                if (i > 0) key.append('|');
                key.append(ip[i] - origin[i]);
            }
            Integer addr = artifact.relativeCoordToLinearAddress().get(key.toString());
            if (addr != null) {
                var si = artifact.sourceMap().get(addr);
                if (si != null) { fileName = si.fileName(); lineNumber = si.lineNumber(); }
            }
        }
        return new DisassembledInstruction(opcodeName, arguments, fileName, lineNumber);
    }

    private String resolveRegisterName(int regId, ProgramArtifact artifact) {
        if (regId >= Instruction.FPR_BASE) return "%FPR" + (regId - Instruction.FPR_BASE);
        if (regId >= Instruction.PR_BASE) return "%PR" + (regId - Instruction.PR_BASE);
        return "%DR" + regId;
    }

    private String formatRuntimeValue(Object value) {
        if (value == null) return "null";
        if (value instanceof int[] vec) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < vec.length; i++) {
                if (i > 0) sb.append('|');
                sb.append(vec[i]);
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof Integer i) {
            return Molecule.fromInt(i).toString();
        }
        return value.toString();
    }
}