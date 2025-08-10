package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.IWorldModifyingInstruction;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class WorldInteractionInstruction extends Instruction implements IWorldModifyingInstruction {

    private int[] targetCoordinate; // Wird zur Laufzeit für die Konfliktlösung gesetzt

    public WorldInteractionInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        try {
            List<Operand> operands = resolveOperands(simulation.getWorld());
            String opName = getName();
            World world = simulation.getWorld();

            // Bestimme das Ziel und den Vektor basierend auf dem Befehl
            Object valueToWrite = null;
            int[] vector;
            int targetReg = -1;

            if (opName.endsWith("S")) { // Stack-Varianten (SCNS, SEKS, PEKS, POKS)
                vector = (int[]) operands.get(0).value();
                if (opName.equals("POKS")) {
                    valueToWrite = operands.get(1).value();
                }
            } else if (opName.equals("SEKI") || opName.equals("SEEK")) {
                vector = (int[]) operands.get(0).value();
            } else { // Register- und Immediate-Varianten
                targetReg = operands.get(0).rawSourceId();
                vector = (int[]) operands.get(1).value();
                if (opName.startsWith("POKE")) {
                    valueToWrite = readOperand(targetReg);
                }
            }

            this.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, world);

            switch (opName) {
                case "PEEK", "PEKI", "PEKS" -> handlePeek(world, targetReg);
                case "POKE", "POKI", "POKS" -> handlePoke(world, valueToWrite);
                case "SCAN", "SCNI", "SCNS" -> handleScan(world, targetReg);
                case "SEEK", "SEKI", "SEKS" -> handleSeek(world);
                default -> organism.instructionFailed("Unknown world interaction: " + opName);
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during world interaction.");
        } catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
            organism.instructionFailed("Invalid operand types for world interaction.");
        }
    }

    private void handlePeek(World world, int targetReg) {
        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            Symbol s = world.getSymbol(targetCoordinate);
            if (s.isEmpty()) {
                organism.instructionFailed("PEEK: Target cell is empty.");
                setConflictStatus(ConflictResolutionStatus.LOST_TARGET_EMPTY);
                return;
            }

            Object valueToStore;
            if (s.type() == Config.TYPE_ENERGY) {
                int energyToTake = Math.min(s.toScalarValue(), Config.MAX_ORGANISM_ENERGY - organism.getEr());
                organism.addEr(energyToTake);
                valueToStore = new Symbol(Config.TYPE_ENERGY, energyToTake).toInt();
            } else {
                valueToStore = s.toInt();
            }

            if (getName().endsWith("S")) { // PEKS
                organism.getDataStack().push(valueToStore);
            } else { // PEEK, PEKI
                writeOperand(targetReg, valueToStore);
            }
            world.setSymbol(new Symbol(Config.TYPE_CODE, 0), targetCoordinate);
        }
    }

    private void handlePoke(World world, Object valueToWrite) {
        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            if (valueToWrite instanceof int[]) {
                organism.instructionFailed("POKE: Cannot write vectors to the world.");
                return;
            }
            if (world.getSymbol(targetCoordinate).isEmpty()) {
                world.setSymbol(Symbol.fromInt((Integer)valueToWrite), targetCoordinate);
            } else {
                organism.instructionFailed("POKE: Target cell is not empty.");
                setConflictStatus(ConflictResolutionStatus.LOST_TARGET_OCCUPIED);
            }
        }
    }

    private void handleScan(World world, int targetReg) {
        Symbol s = world.getSymbol(targetCoordinate);
        if (getName().endsWith("S")) { // SCNS
            organism.getDataStack().push(s.toInt());
        } else { // SCAN, SCNI
            writeOperand(targetReg, s.toInt());
        }
    }

    private void handleSeek(World world) {
        if (world.getSymbol(targetCoordinate).isEmpty()) {
            organism.setDp(targetCoordinate);
        } else {
            organism.instructionFailed("SEEK: Target cell is not empty.");
        }
    }


    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        WorldInteractionInstruction instruction = new WorldInteractionInstruction(organism, fullOpcodeId);
        try {
            List<Operand> operands = instruction.resolveOperands(world);
            String opName = instruction.getName();
            int[] vector;
            if (opName.endsWith("S")) { // SCNS, SEKS, PEKS, POKS
                vector = (int[]) operands.get(0).value();
            } else if (opName.equals("SEKI") || opName.equals("SEEK")) {
                vector = (int[]) operands.get(0).value();
            } else { // PEEK, POKE, SCAN mit Register + Vektor
                vector = (int[]) operands.get(1).value();
            }
            instruction.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, world);
        } catch (Exception ignored) {
            // Falls Operanden beim Planen nicht auflösbar sind, bleibt das Ziel leer;
            // die Ausführung behandelt das entsprechend.
        }
        return instruction;
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        String name = instructionName.toUpperCase();

        if (name.endsWith("S")) { // SCNS, SEKS, PEKS, POKS
            if (args.length != 0) throw new IllegalArgumentException(name + " expects no arguments.");
            return new AssemblerOutput.CodeSequence(List.of());
        } else if (name.endsWith("I")) { // PEKI, POKI, SCNI, SEKI
            if (name.equals("SEKI")) {
                if (args.length != 1) throw new IllegalArgumentException("SEKI expects 1 vector argument.");
            } else {
                if (args.length != 2) throw new IllegalArgumentException(name + " expects a register and a vector.");
            }

            Integer reg = name.equals("SEKI") ? 0 : resolveRegToken(args[0], registerMap);
            if (reg == null) throw new IllegalArgumentException("Invalid register for " + name);

            String vecArg = name.equals("SEKI") ? args[0] : args[1];
            String[] comps = vecArg.split("\\|");
            if (comps.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Invalid vector dimensionality for " + name);

            List<Integer> machineCode = new ArrayList<>();
            if (!name.equals("SEKI")) {
                machineCode.add(new Symbol(Config.TYPE_DATA, reg).toInt());
            }
            for (String c : comps) {
                machineCode.add(new Symbol(Config.TYPE_DATA, Integer.parseInt(c.strip())).toInt());
            }
            return new AssemblerOutput.CodeSequence(machineCode);
        } else { // PEEK, POKE, SCAN, SEEK
            if (name.equals("SEEK")) {
                if (args.length != 1) throw new IllegalArgumentException("SEEK expects 1 vector register argument.");
                Integer vecReg = resolveRegToken(args[0], registerMap);
                if (vecReg == null) throw new IllegalArgumentException("Invalid register for SEEK");
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, vecReg).toInt()));
            } else {
                if (args.length != 2) throw new IllegalArgumentException(name + " expects two register arguments.");
                Integer reg1 = resolveRegToken(args[0], registerMap);
                Integer reg2 = resolveRegToken(args[1], registerMap);
                if (reg1 == null || reg2 == null) throw new IllegalArgumentException("Invalid register for " + name);
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, reg1).toInt(), new Symbol(Config.TYPE_DATA, reg2).toInt()));
            }
        }
    }

    @Override
    public int getLength() {
        String name = getName();
        return switch (name) {
            case "SEEK" -> 1;                           // ein Vektor-Register
            case "SEKI" -> Config.WORLD_DIMENSIONS;     // unmittelbarer Vektor (x|y|…)
            case "PEEK", "POKE", "SCAN" -> 2;           // Register + Vektor-Register
            default -> super.getLength();
        };
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        // This needs to be calculated during planning/execution, as it depends on runtime DP.
        // The `execute` method now sets this field.
        return (targetCoordinate != null) ? List.of(targetCoordinate) : List.of();
    }
}
