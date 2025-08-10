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

    private int[] targetCoordinate;

    public WorldInteractionInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        try {
            List<Operand> operands = resolveOperands(simulation.getWorld());
            String opName = getName();
            World world = simulation.getWorld();
            Object valueToWrite = null;
            int[] vector;
            int targetReg = -1;

            if (opName.endsWith("S")) {
                vector = (int[]) operands.get(0).value();
                if (opName.equals("POKS")) {
                    valueToWrite = operands.get(1).value();
                }
            } else {
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
                if (getConflictStatus() != ConflictResolutionStatus.NOT_APPLICABLE) setConflictStatus(ConflictResolutionStatus.LOST_TARGET_EMPTY);
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

            if (getName().endsWith("S")) {
                organism.getDataStack().push(valueToStore);
            } else {
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
                if (getConflictStatus() != ConflictResolutionStatus.NOT_APPLICABLE) setConflictStatus(ConflictResolutionStatus.LOST_TARGET_OCCUPIED);
            }
        }
    }

    private void handleScan(World world, int targetReg) {
        Symbol s = world.getSymbol(targetCoordinate);
        if (getName().endsWith("S")) {
            organism.getDataStack().push(s.toInt());
        } else {
            writeOperand(targetReg, s.toInt());
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new WorldInteractionInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        String name = instructionName.toUpperCase();
        if (name.endsWith("S")) {
            if (args.length != 0) throw new IllegalArgumentException(name + " expects no arguments.");
            return new AssemblerOutput.CodeSequence(List.of());
        } else if (name.endsWith("I")) {
            if (args.length != 2) throw new IllegalArgumentException(name + " expects a register and a vector.");
            Integer reg = resolveRegToken(args[0], registerMap);
            if (reg == null) throw new IllegalArgumentException("Invalid register for " + name);
            String[] comps = args[1].split("\\|");
            if (comps.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Invalid vector dimensionality.");
            List<Integer> machineCode = new ArrayList<>();
            machineCode.add(new Symbol(Config.TYPE_DATA, reg).toInt());
            for (String c : comps) {
                machineCode.add(new Symbol(Config.TYPE_DATA, Integer.parseInt(c.strip())).toInt());
            }
            return new AssemblerOutput.CodeSequence(machineCode);
        } else {
            if (args.length != 2) throw new IllegalArgumentException(name + " expects two register arguments.");
            Integer reg1 = resolveRegToken(args[0], registerMap);
            Integer reg2 = resolveRegToken(args[1], registerMap);
            if (reg1 == null || reg2 == null) throw new IllegalArgumentException("Invalid register for " + name);
            return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, reg1).toInt(), new Symbol(Config.TYPE_DATA, reg2).toInt()));
        }
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        if (this.targetCoordinate == null) {
            // Re-calculate target coordinate on demand if not already set by execute()
            try {
                List<Operand> operands = resolveOperands(organism.getSimulation().getWorld());
                if (operands.isEmpty()) return List.of();
                Object vectorOperandValue = null;
                if (getName().endsWith("S")) {
                    if (!operands.isEmpty()) vectorOperandValue = operands.get(0).value();
                } else if (operands.size() > 1) {
                    vectorOperandValue = operands.get(1).value();
                }

                if (vectorOperandValue instanceof int[]) {
                    this.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), (int[])vectorOperandValue, organism.getSimulation().getWorld());
                }
            } catch (Exception e) {
                return List.of();
            }
        }
        return (targetCoordinate != null) ? List.of(targetCoordinate) : List.of();
    }
}