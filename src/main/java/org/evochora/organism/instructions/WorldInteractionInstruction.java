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
            Object valueToWrite;
            int[] vector;

            if ("POKS".equals(opName)) {
                if (operands.size() >= 2) {
                    Object value = operands.get(0).value();
                    vector = (int[]) operands.get(1).value();
                    valueToWrite = value;
                } else {
                    // Fallback: read from stack (top=value, next=vector)
                    Object value = organism.getDataStack().pop();
                    Object vecObj = organism.getDataStack().pop();
                    if (!(vecObj instanceof int[])) {
                        organism.instructionFailed("POKS requires a vector on stack.");
                        return;
                    }
                    valueToWrite = value;
                    vector = (int[]) vecObj;
                }
            } else if ("POKE".equals(opName) || "POKI".equals(opName)) {
                if (operands.size() >= 2) {
                    int targetReg = operands.get(0).rawSourceId();
                    vector = (int[]) operands.get(1).value();
                    valueToWrite = readOperand(targetReg);
                } else {
                    // Fallback: decode directly from world arguments
                    int[] ip = organism.getIpBeforeFetch();
                    // Skip opcode cell
                    int[] next = organism.getNextInstructionPosition(ip, world, organism.getDvBeforeFetch());
                    // First arg: register id for value
                    int regId = Symbol.fromInt(world.getSymbol(next).toInt()).toScalarValue();
                    valueToWrite = readOperand(regId);

                    if ("POKE".equals(opName)) {
                        // Second arg: register id holding vector
                        next = organism.getNextInstructionPosition(next, world, organism.getDvBeforeFetch());
                        int vecRegId = Symbol.fromInt(world.getSymbol(next).toInt()).toScalarValue();
                        Object vecObj = readOperand(vecRegId);
                        if (!(vecObj instanceof int[])) {
                            organism.instructionFailed("POKE requires vector in register.");
                            return;
                        }
                        vector = (int[]) vecObj;
                    } else {
                        // POKI: following WORLD_DIMENSIONS ints form a vector
                        int dims = world.getShape().length;
                        vector = new int[dims];
                        for (int d = 0; d < dims; d++) {
                            next = organism.getNextInstructionPosition(next, world, organism.getDvBeforeFetch());
                            vector[d] = Symbol.fromInt(world.getSymbol(next).toInt()).toScalarValue();
                        }
                    }
                }
            } else {
                organism.instructionFailed("Unknown world interaction: " + opName);
                return;
            }

            // Enforce unit vector
            if (!organism.isUnitVector(vector)) {
                organism.instructionFailed(opName + " requires a unit vector.");
                return;
            }

            this.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, world);

            switch (opName) {
                case "POKE", "POKI", "POKS" -> handlePoke(world, valueToWrite);
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
            // Charge energy equal to the absolute payload of the symbol being written
            Symbol toWrite = Symbol.fromInt((Integer) valueToWrite);
            int cost = Math.abs(toWrite.toScalarValue());
            if (cost > 0) organism.takeEr(cost);

            if (world.getSymbol(targetCoordinate).isEmpty()) {
                world.setSymbol(toWrite, targetCoordinate);
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
        if (name.equals("POKS")) {
            if (args.length != 0) throw new IllegalArgumentException("POKS expects no arguments.");
            return new AssemblerOutput.CodeSequence(List.of());
        } else if (name.equals("POKI")) {
            if (args.length != 2) throw new IllegalArgumentException("POKI expects a register and a vector.");
            Integer reg = resolveRegToken(args[0], registerMap);
            if (reg == null) throw new IllegalArgumentException("Invalid register for POKI");
            String[] comps = args[1].split("\\|");
            if (comps.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Invalid vector dimensionality.");
            List<Integer> machineCode = new ArrayList<>();
            machineCode.add(new Symbol(Config.TYPE_DATA, reg).toInt());
            for (String c : comps) {
                machineCode.add(new Symbol(Config.TYPE_DATA, Integer.parseInt(c.strip())).toInt());
            }
            return new AssemblerOutput.CodeSequence(machineCode);
        } else if (name.equals("POKE")) {
            if (args.length != 2) throw new IllegalArgumentException("POKE expects two register arguments.");
            Integer reg1 = resolveRegToken(args[0], registerMap);
            Integer reg2 = resolveRegToken(args[1], registerMap);
            if (reg1 == null || reg2 == null) throw new IllegalArgumentException("Invalid register for POKE");
            return new AssemblerOutput.CodeSequence(List.of(
                    new Symbol(Config.TYPE_DATA, reg1).toInt(),
                    new Symbol(Config.TYPE_DATA, reg2).toInt()
            ));
        }
        throw new IllegalArgumentException("Unknown world interaction instruction for assembler: " + name);
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        if (this.targetCoordinate == null) {
            try {
                World world = organism.getSimulation().getWorld();
                String op = getName();
                int[] vec = null;

                if ("POKS".equals(op)) {
                    // Non-destructive peek: top=value, next=vector
                    java.util.Iterator<Object> it = organism.getDataStack().iterator();
                    if (it.hasNext()) {
                        it.next(); // skip top (value)
                        if (it.hasNext()) {
                            Object nxt = it.next();
                            if (nxt instanceof int[]) vec = (int[]) nxt;
                        }
                    }
                } else if ("POKE".equals(op)) {
                    // Decode from instruction arguments: [regId(value), regId(vector)]
                    int[] ip = organism.getIpBeforeFetch();
                    int[] a1 = organism.getNextInstructionPosition(ip, world, organism.getDvBeforeFetch()); // regId(value) - unused here
                    int[] a2 = organism.getNextInstructionPosition(a1, world, organism.getDvBeforeFetch()); // regId(vector)
                        int vecRegId = Symbol.fromInt(world.getSymbol(a2).toInt()).toScalarValue();
                    Object regVal = readOperand(vecRegId);
                    if (regVal instanceof int[]) vec = (int[]) regVal;
                } else if ("POKI".equals(op)) {
                    // Decode from instruction args: [regId(value), v0, v1, ...]
                    int dims = world.getShape().length;
                    int[] ip = organism.getIpBeforeFetch();
                    int[] cur = organism.getNextInstructionPosition(ip, world, organism.getDvBeforeFetch()); // skip regId
                    int[] tmp = new int[dims];
                    for (int d = 0; d < dims; d++) {
                        cur = organism.getNextInstructionPosition(cur, world, organism.getDvBeforeFetch());
                        tmp[d] = org.evochora.world.Symbol.fromInt(world.getSymbol(cur).toInt()).toScalarValue();
                    }
                    vec = tmp;
                } else {
                    return List.of();
                }

                if (vec != null && organism.isUnitVector(vec)) {
                    this.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vec, world);
                } else {
                    // Not a unit vector or cannot determine -> no target
                    return List.of();
                }
            } catch (Exception e) {
                return List.of();
            }
        }
        return (targetCoordinate != null) ? List.of(targetCoordinate) : List.of();
    }
}