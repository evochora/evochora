package org.evochora.server.indexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.api.DisassembledArgument;
import org.evochora.runtime.api.DisassembledInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.runtime.internal.services.ExecutionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RuntimeDisassembler {

    public static final RuntimeDisassembler INSTANCE = new RuntimeDisassembler();
    private RuntimeDisassembler() {}

    /**
     * Disassembly mit Compiler-Daten (Source-Mapping, etc.)
     * Für WebDebugger und SourceView mit vollständigen Informationen.
     */
    public DisassembledInstruction disassembleWithCompilerData(ExecutionContext context, ProgramArtifact artifact) {
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
                    int dims = environment.getShape().length;
                    int[] vec = new int[dims];
                    vec[0] = argValue;
                    int[] next = currentArgCoord;
                    for (int i = 1; i < dims; i++) {
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
                    int dims = environment.getShape().length;
                    int[] vec = new int[dims];
                    vec[0] = argValue;
                    int[] next = currentArgCoord;
                    for (int i = 1; i < dims; i++) {
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

    /**
     * Disassembly nur mit Runtime-Daten (ISA + VM)
     * Für buildNextInstruction ohne ProgramArtifact-Abhängigkeiten.
     * Funktioniert immer, auch bei FORK-Organismen oder Code-Mutationen.
     */
    public DisassembledInstruction disassembleRuntimeOnly(RawOrganismState organism) {
        // Extrahiere IP und DV aus dem Organism
        int[] ip = organism.ip();
        int[] dv = organism.dv();
        
        // Lade den Opcode an der aktuellen IP-Position
        // Da wir keine Environment haben, müssen wir den Opcode aus den VM-Daten rekonstruieren
        // Für jetzt verwenden wir einen Dummy-Wert - das muss später implementiert werden
        int opcodeId = 0; // TODO: Aus VM-Daten laden
        
        String opcodeName = Instruction.getInstructionNameById(opcodeId);
        if ("UNKNOWN".equals(opcodeName)) {
            return new DisassembledInstruction("UNKNOWN_OP", List.of(), null, null);
        }

        List<DisassembledArgument> arguments = new ArrayList<>();
        Optional<InstructionSignature> signatureOpt = Instruction.getSignatureById(opcodeId);
        if (signatureOpt.isEmpty()) {
            return new DisassembledInstruction(opcodeName, arguments, null, null);
        }

        InstructionSignature signature = signatureOpt.get();
        
        // Parse Argumente basierend auf der ISA-Signatur
        for (InstructionArgumentType argType : signature.argumentTypes()) {
            DisassembledArgument arg = parseArgument(argType, organism, ip, dv);
            if (arg != null) {
                arguments.add(arg);
            }
        }

        return new DisassembledInstruction(opcodeName, arguments, null, null);
    }

    /**
     * Parst ein einzelnes Argument basierend auf dem Argument-Typ.
     */
    private DisassembledArgument parseArgument(InstructionArgumentType argType, RawOrganismState organism, int[] ip, int[] dv) {
        switch (argType) {
            case REGISTER: {
                // Lade den Register-Wert aus der VM
                int regId = 0; // TODO: Aus VM-Daten laden
                String regName = resolveRegisterName(regId, null);
                Object regValue = readOperand(organism, regId);
                String display = formatRuntimeValue(regValue);
                return new DisassembledArgument("register", regName, regId, display);
            }
            case VECTOR: {
                // Lade den Vektor aus der VM
                int[] vector = new int[0]; // TODO: Aus VM-Daten laden
                String display = formatVector(vector);
                return new DisassembledArgument("vector", display, 0, display);
            }
            case LABEL: {
                // Labels werden wie Vektoren behandelt
                int[] vector = new int[0]; // TODO: Aus VM-Daten laden
                String display = formatVector(vector);
                return new DisassembledArgument("label", display, 0, display);
            }
            default: {
                // Für andere Typen (IMMEDIATE, etc.)
                int value = 0; // TODO: Aus VM-Daten laden
                return new DisassembledArgument(argType.toString().toLowerCase(), String.valueOf(value), value, String.valueOf(value));
            }
        }
    }

    /**
     * Lest einen Operand aus dem RawOrganismState.
     */
    private Object readOperand(RawOrganismState organism, int regId) {
        if (regId >= Instruction.FPR_BASE) {
            int index = regId - Instruction.FPR_BASE;
            if (index < organism.fprs().size()) {
                return organism.fprs().get(index);
            }
        } else if (regId >= Instruction.PR_BASE) {
            int index = regId - Instruction.PR_BASE;
            if (index < organism.prs().size()) {
                return organism.prs().get(index);
            }
        } else {
            if (regId < organism.drs().size()) {
                return organism.drs().get(regId);
            }
        }
        return 0;
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
        return String.valueOf(value);
    }

    private String formatVector(int[] vector) {
        if (vector == null) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
