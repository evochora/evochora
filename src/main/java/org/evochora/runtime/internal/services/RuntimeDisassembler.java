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
import java.util.Map;
import java.util.Optional;

public class RuntimeDisassembler {

    public static final RuntimeDisassembler INSTANCE = new RuntimeDisassembler();
    private RuntimeDisassembler() {}

    public DisassembledInstruction disassemble(Organism organism, ProgramArtifact artifact, Environment environment) {
        int[] ip = organism.getIp(); // Wir nehmen den aktuellen IP für die *nächste* Instruktion
        int[] dv = organism.getDv();
        Molecule molecule = environment.getMolecule(ip);

        int opcodeId = molecule.toInt();
        String opcodeName = Instruction.getInstructionNameById(opcodeId);

        if ("UNKNOWN".equals(opcodeName)) {
            return new DisassembledInstruction("UNKNOWN_OP (" + molecule.value() + ")", List.of());
        }

        List<DisassembledArgument> arguments = new ArrayList<>();
        Optional<InstructionSignature> signatureOpt = Instruction.getSignatureById(opcodeId);
        if (signatureOpt.isEmpty()) {
            return new DisassembledInstruction(opcodeName, arguments);
        }

        InstructionSignature signature = signatureOpt.get();
        int[] currentArgCoord = ip;

        for (InstructionArgumentType argType : signature.argumentTypes()) {
            currentArgCoord = organism.getNextInstructionPosition(currentArgCoord, environment, dv);
            Molecule argMol = environment.getMolecule(currentArgCoord);
            int argValue = argMol.toScalarValue();
            String argDisplay = argMol.toString();
            String argName = argDisplay;

            if (argType == InstructionArgumentType.REGISTER) {
                argName = resolveRegisterName(argValue, artifact);
                arguments.add(new DisassembledArgument("register", argName, argValue, argDisplay));
            } else {
                arguments.add(new DisassembledArgument(argType.toString().toLowerCase(), argName, argValue, argDisplay));
            }
        }
        return new DisassembledInstruction(opcodeName, arguments);
    }

    private String resolveRegisterName(int regId, ProgramArtifact artifact) {
        // In Zukunft könnte hier die Register-Map aus dem Artefakt genutzt werden, um Aliase aufzulösen.
        if (regId >= Instruction.FPR_BASE) return "%FPR" + (regId - Instruction.FPR_BASE);
        if (regId >= Instruction.PR_BASE) return "%PR" + (regId - Instruction.PR_BASE);
        return "%DR" + regId;
    }
}