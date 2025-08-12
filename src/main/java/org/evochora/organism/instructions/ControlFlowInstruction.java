package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.assembler.AssemblyProgram;
import org.evochora.assembler.ProgramMetadata;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class ControlFlowInstruction extends Instruction {

    // VM-internal registry for DR<->FPR bindings at CALL sites, keyed by linear address
    private static final java.util.Map<Integer, int[]> CALL_BINDINGS_BY_LINEAR_ADDRESS = new java.util.concurrent.ConcurrentHashMap<>();
    // Fallback registry keyed by absolute world coordinate (List<Integer>)
    private static final java.util.Map<java.util.List<Integer>, int[]> CALL_BINDINGS_BY_ABS_COORD = new java.util.concurrent.ConcurrentHashMap<>();

    public static void registerCallBindingForLinearAddress(int linearAddress, int[] drIds) {
        CALL_BINDINGS_BY_LINEAR_ADDRESS.put(linearAddress, java.util.Arrays.copyOf(drIds, drIds.length));
    }

    public static void registerCallBindingForAbsoluteCoord(int[] absoluteCoord, int[] drIds) {
        java.util.List<Integer> key = java.util.Arrays.stream(absoluteCoord).boxed().toList();
        CALL_BINDINGS_BY_ABS_COORD.put(key, java.util.Arrays.copyOf(drIds, drIds.length));
    }

    private static int[] getBindingForLinearAddress(int linearAddress) {
        return CALL_BINDINGS_BY_LINEAR_ADDRESS.get(linearAddress);
    }

    private static int[] getBindingForAbsoluteCoord(int[] absoluteCoord) {
        java.util.List<Integer> key = java.util.Arrays.stream(absoluteCoord).boxed().toList();
        return CALL_BINDINGS_BY_ABS_COORD.get(key);
    }

    public ControlFlowInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        String opName = getName();
        try {
            switch (opName) {
                case "JMPI", "CALL" -> {
                    List<Operand> operands = resolveOperands(simulation.getWorld());
                    int[] delta = (int[]) operands.get(0).value();
                    int[] callIp = organism.getIpBeforeFetch();
                    int[] targetIp = organism.getTargetCoordinate(callIp, delta, simulation.getWorld());

                    if (opName.equals("CALL")) {
                        // --- NEUE Copy-In/Copy-out Logik ---
                        String programId = AssemblyProgram.getProgramIdForOrganism(organism);
                        ProgramMetadata metadata = (programId != null) ? AssemblyProgram.getMetadataForProgram(programId) : null;
                        int[] programOrigin = organism.getInitialPosition();
                        int[] relativeIp = new int[callIp.length];
                        for(int i=0; i<callIp.length; i++) relativeIp[i] = callIp[i] - programOrigin[i];

                        Integer callLinearAddress = (metadata != null) ? metadata.relativeCoordToLinearAddress().get(java.util.Arrays.stream(relativeIp).boxed().collect(java.util.stream.Collectors.toList())) : null;
                        if(callLinearAddress == null) {
                            // Fallback for dynamic code ohne Metadaten. Sollte im Normalfall nicht vorkommen.
                            callLinearAddress = -1;
                        }

                        // Bevorzugt: Bindings aus den Assembler-Metadaten (PassManager) verwenden
                        int[] binding = null;
                        if (metadata != null && callLinearAddress >= 0) {
                            binding = metadata.callSiteBindings().get(callLinearAddress);
                        }

                        // Zusätzlicher Fallback: absolute Koordinate verwenden (kein ProgramMetadata erforderlich)
                        if (binding == null) {
                            binding = getBindingForAbsoluteCoord(callIp);
                        }

                        // Fallback: Versuch, .WITH direkt aus der Quelldatei beim Call-Site zu parsen
                        if (binding == null && metadata != null && callLinearAddress >= 0) {
                            org.evochora.assembler.SourceLocation src = metadata.linearAddressToSourceLocation().get(callLinearAddress);
                            if (src != null) {
                                String content = src.lineContent();
                                if (content != null) {
                                    String stripped = content.split("#", 2)[0].strip();
                                    String upper = stripped.toUpperCase(java.util.Locale.ROOT);
                                    if (upper.startsWith("CALL ") && (upper.contains(".WITH") || upper.contains(" WITH"))) {
                                        int withIdx = upper.indexOf(".WITH");
                                        int tokenLen = ".WITH".length();
                                        if (withIdx < 0) {
                                            withIdx = upper.indexOf(" WITH");
                                            tokenLen = " WITH".length();
                                        }
                                        String afterWith = stripped.substring(withIdx + tokenLen).trim();
                                        if (!afterWith.isEmpty()) {
                                            String[] regTokens = afterWith.split("\\s+");
                                            int[] drIds = new int[regTokens.length];
                                            boolean ok = true;
                                            java.util.Map<String, Integer> regMap = metadata.registerMap();
                                            for (int i = 0; i < regTokens.length; i++) {
                                                Integer id = org.evochora.organism.Instruction.resolveRegToken(regTokens[i], regMap);
                                                if (id == null) { ok = false; break; }
                                                drIds[i] = id;
                                            }
                                            if (ok) {
                                                binding = drIds;
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        java.util.Map<Integer, Integer> fprBindings = new java.util.HashMap<>();
                        Object[] newFprValues = new Object[Config.NUM_FORMAL_PARAM_REGISTERS];

                        // 1. Copy-In Phase
                        if (binding != null) {
                            for (int i = 0; i < binding.length; i++) {
                                int sourceRegId = binding[i];
                                // Wert aus dem Register des Aufrufers lesen (kann DR, PR oder FPR sein)
                                Object value = readOperand(sourceRegId);
                                newFprValues[i] = value;
                                // Binding für die Copy-Out Phase speichern
                                fprBindings.put(FPR_BASE + i, sourceRegId);
                            }
                        }

                        // Rücksprungadresse berechnen
                        int[] absoluteReturnIp = callIp;
                        for (int i = 0; i < getLength(); i++) {
                            absoluteReturnIp = organism.getNextInstructionPosition(absoluteReturnIp, simulation.getWorld(), organism.getDvBeforeFetch());
                        }
                        int[] initialPosition = organism.getInitialPosition();
                        int[] relativeReturnIp = new int[absoluteReturnIp.length];
                        for (int i = 0; i < absoluteReturnIp.length; i++) {
                            relativeReturnIp[i] = absoluteReturnIp[i] - initialPosition[i];
                        }

                        // Alten Zustand sichern und Frame auf den Call-Stack pushen
                        Object[] prSnapshot = organism.getPrs().toArray(new Object[0]);
                        Object[] fprSnapshot = organism.getFprs().toArray(new Object[0]);
                        if (organism.getCallStack().size() >= Config.CALL_STACK_MAX_DEPTH) {
                            organism.instructionFailed("Call stack overflow");
                            return;
                        }
                        String procName = "UNKNOWN"; // Prozedurname für Debugging holen
                        if (metadata != null) {
                            programOrigin = organism.getInitialPosition();
                            int[] relativeTargetIp = new int[targetIp.length];
                            for (int i = 0; i < targetIp.length; i++) {
                                relativeTargetIp[i] = targetIp[i] - programOrigin[i];
                            }
                            List<Integer> coordAsList = java.util.Arrays.stream(relativeTargetIp).boxed().collect(java.util.stream.Collectors.toList());
                            Integer targetLinearAddress = metadata.relativeCoordToLinearAddress().get(coordAsList);
                            if (targetLinearAddress != null) {
                                procName = metadata.labelAddressToName().get(targetLinearAddress);
                            }
                        }
                        organism.getCallStack().push(new Organism.ProcFrame(procName, relativeReturnIp, prSnapshot, fprSnapshot, fprBindings));

                        // 2. Load: Die neuen FPR-Werte in den Organismus laden (nur für gebundene Parameter)
                        for (int i = 0; i < newFprValues.length; i++) {
                            if (fprBindings.containsKey(FPR_BASE + i)) {
                                organism.setFpr(i, newFprValues[i]);
                            }
                        }
                    }
                    organism.setIp(targetIp);
                    organism.setSkipIpAdvance(true);
                }
                case "JMPR" -> {
                    List<Operand> operands = resolveOperands(simulation.getWorld());
                    int[] relativeCoord = (int[]) operands.get(0).value();
                    int[] initialPosition = organism.getInitialPosition();
                    int[] absoluteCoord = new int[relativeCoord.length];
                    for (int i = 0; i < relativeCoord.length; i++) {
                        absoluteCoord[i] = initialPosition[i] + relativeCoord[i];
                    }
                    organism.setIp(absoluteCoord);
                    organism.setSkipIpAdvance(true);
                }
                case "JMPS" -> {
                    List<Operand> operands = resolveOperands(simulation.getWorld());
                    int[] vec = (int[]) operands.get(0).value();
                    int[] target = organism.getTargetCoordinate(organism.getIpBeforeFetch(), vec, simulation.getWorld());
                    organism.setIp(target);
                    organism.setSkipIpAdvance(true);
                }
                case "RET" -> {
                    // --- NEUE Copy-In/Copy-Out Logik ---
                    Deque<Organism.ProcFrame> cs = organism.getCallStack();
                    if (cs.isEmpty()) {
                        organism.instructionFailed("Return from empty call stack.");
                        return;
                    }
                    Organism.ProcFrame frame = cs.pop();

                    // 1. Copy-Out Phase
                    for (Map.Entry<Integer, Integer> entry : frame.fprBindings.entrySet()) {
                        int fprId = entry.getKey();
                        int targetRegId = entry.getValue();
                        Object value = organism.getFpr(fprId - FPR_BASE); // Wert aus dem aktuellen FPR holen

                        // Normalize plain integers to DATA symbols when writing to DRs
                        if (targetRegId < PR_BASE && value instanceof Integer intVal) {
                            // Treat raw scalar as DATA-typed symbol
                            value = new org.evochora.world.Symbol(org.evochora.Config.TYPE_DATA, intVal).toInt();
                        }

                        writeOperand(targetRegId, value); // Wert in das Register des Aufrufers schreiben
                    }

                    // 2. Zustand des Aufrufers wiederherstellen
                    organism.restorePrs(frame.savedPrs);
                    organism.restoreFprs(frame.savedFprs);

                    // IP wiederherstellen
                    int[] initialPosition = organism.getInitialPosition();
                    int[] absoluteReturnIp = new int[frame.relativeReturnIp.length];
                    for (int i = 0; i < frame.relativeReturnIp.length; i++) {
                        absoluteReturnIp[i] = initialPosition[i] + frame.relativeReturnIp[i];
                    }

                    organism.setIp(absoluteReturnIp);
                    organism.setSkipIpAdvance(true);
                }
                default -> organism.instructionFailed("Unknown control flow instruction: " + opName);
            }
        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during control flow operation.");
        } catch (ClassCastException e) {
            organism.instructionFailed("Invalid operand type for control flow operation.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new ControlFlowInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        String name = instructionName.toUpperCase();

        switch (name) {
            case "JMPI", "CALL":
                if (args.length != 1) throw new IllegalArgumentException(name + " expects 1 label argument.");
                return new AssemblerOutput.JumpInstructionRequest(args[0]);
            case "JMPR":
                if (args.length != 1) throw new IllegalArgumentException("JMPR expects 1 register argument.");
                Integer regId = resolveRegToken(args[0], registerMap);
                if (regId == null) throw new IllegalArgumentException("Invalid register for JMPR.");
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, regId).toInt()));
            case "JMPS":
            case "RET":
                if (args.length != 0) throw new IllegalArgumentException(name + " expects no arguments.");
                return new AssemblerOutput.CodeSequence(Collections.emptyList());
        }
        throw new IllegalArgumentException("Cannot assemble unknown control flow instruction: " + name);
    }
}
