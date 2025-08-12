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
import java.util.Iterator;
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
            String opName = getName();

            // KORREKTUR: Die Prüfung wurde von startsWith auf eine exakte Überprüfung aller
            // Varianten umgestellt. Das behebt den Fehler.
            if ("POKE".equals(opName) || "POKI".equals(opName) || "POKS".equals(opName)) {
                handlePoke(simulation);
            } else if ("PEEK".equals(opName) || "PEKI".equals(opName) || "PEKS".equals(opName)) {
                handlePeek(simulation);
            } else {
                organism.instructionFailed("Unknown world interaction instruction: " + opName);
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Invalid operands for " + getName());
        } catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
            organism.instructionFailed("Invalid operand types for world interaction.");
        }
    }

    private void handlePoke(Simulation simulation) {
        List<Operand> operands = resolveOperands(simulation.getWorld());
        Object valueToWrite;
        int[] vector;

        if ("POKS".equals(getName())) {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for POKS."); return; }
            valueToWrite = operands.get(0).value();
            vector = (int[]) operands.get(1).value();
        } else {
            if (operands.size() < 2) { organism.instructionFailed("Invalid operands for POKE/POKI."); return; }
            valueToWrite = readOperand(operands.get(0).rawSourceId());
            vector = (int[]) operands.get(1).value();
        }

        if (this.targetCoordinate == null) {
            this.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, simulation.getWorld());
        }

        if (getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            if (valueToWrite instanceof int[]) {
                organism.instructionFailed("POKE: Cannot write vectors to the world.");
                return;
            }
            Symbol toWrite = Symbol.fromInt((Integer) valueToWrite);
            int cost = Math.abs(toWrite.toScalarValue());
            if (cost > 0) organism.takeEr(cost);

            if (simulation.getWorld().getSymbol(targetCoordinate).isEmpty()) {
                simulation.getWorld().setSymbol(toWrite, targetCoordinate);
            } else {
                organism.instructionFailed("POKE: Target cell is not empty.");
                if (getConflictStatus() != ConflictResolutionStatus.NOT_APPLICABLE) setConflictStatus(ConflictResolutionStatus.LOST_TARGET_OCCUPIED);
            }
        }
    }

    private void handlePeek(Simulation simulation) {
        List<Operand> operands = resolveOperands(simulation.getWorld());
        World world = simulation.getWorld();
        int targetReg;
        int[] vector;

        if (getName().endsWith("S")) {
            if (operands.size() != 1) { organism.instructionFailed("Invalid operands for " + getName()); return; }
            vector = (int[]) operands.get(0).value();
            targetReg = -1;
        } else {
            if (operands.size() != 2) { organism.instructionFailed("Invalid operands for " + getName()); return; }
            targetReg = operands.get(0).rawSourceId();
            vector = (int[]) operands.get(1).value();
        }

        if (this.targetCoordinate == null) {
            this.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, world);
        }

        Symbol s = world.getSymbol(targetCoordinate);

        if (s.isEmpty()) {
            organism.instructionFailed("PEEK: Target cell is empty.");
            return;
        }

        Object valueToStore;
        if (s.type() == Config.TYPE_ENERGY) {
            int energyToTake = Math.min(s.toScalarValue(), Config.MAX_ORGANISM_ENERGY - organism.getEr());
            organism.addEr(energyToTake);
            valueToStore = new Symbol(Config.TYPE_ENERGY, energyToTake).toInt();
        } else {
            if (s.type() == Config.TYPE_STRUCTURE) {
                int ownerId = world.getOwnerId(targetCoordinate);
                if (ownerId != organism.getId()) {
                    int cost = Math.abs(s.toScalarValue());
                    if (cost > 0) organism.takeEr(cost);
                }
            } else {
                int cost = Math.abs(s.toScalarValue());
                if (cost > 0) organism.takeEr(cost);
            }
            valueToStore = s.toInt();
        }

        if (targetReg != -1) {
            writeOperand(targetReg, valueToStore);
        } else {
            organism.getDataStack().push(valueToStore);
        }

        world.setSymbol(new Symbol(Config.TYPE_CODE, 0), targetCoordinate);
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        if (this.targetCoordinate != null) {
            return List.of(this.targetCoordinate);
        }

        try {
            World world = organism.getSimulation().getWorld();
            String opName = getName();
            int[] vector = null;

            int[] currentIp = organism.getIpBeforeFetch();

            if (opName.endsWith("S")) { // POKS, PEKS
                if (organism.getDataStack().size() >= (opName.equals("POKS") ? 2 : 1)) {
                    Iterator<Object> it = organism.getDataStack().iterator();
                    if (opName.equals("POKS")) it.next();
                    Object vecObj = it.next();
                    if (vecObj instanceof int[]) {
                        vector = (int[]) vecObj;
                    }
                }
            } else if (opName.endsWith("I")) { // POKI, PEKI
                int[] vec = new int[Config.WORLD_DIMENSIONS];
                int[] ip = organism.getNextInstructionPosition(currentIp, world, organism.getDvBeforeFetch());
                for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
                    Organism.FetchResult res = organism.fetchSignedArgument(ip, world);
                    vec[i] = res.value();
                    ip = res.nextIp();
                }
                vector = vec;
            } else { // POKE, PEEK
                int[] ip = organism.getNextInstructionPosition(currentIp, world, organism.getDvBeforeFetch());
                Organism.FetchResult vecRegArg = organism.fetchArgument(ip, world);
                int vecRegId = Symbol.fromInt(vecRegArg.value()).toScalarValue();
                Object vecObj = readOperand(vecRegId);
                if (vecObj instanceof int[]) {
                    vector = (int[]) vecObj;
                }
            }

            if (vector != null) {
                this.targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, world);
                return List.of(this.targetCoordinate);
            }

        } catch (Exception e) {
            return List.of();
        }
        return List.of();
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        String name = instructionName.toUpperCase();
        switch (name) {
            case "POKS":
            case "PEKS":
                if (args.length != 0) throw new IllegalArgumentException(name + " expects no arguments.");
                return new AssemblerOutput.CodeSequence(List.of());

            case "POKI":
            case "PEKI": {
                if (args.length != 2) throw new IllegalArgumentException(name + " expects a register and a vector.");
                Integer reg = resolveRegToken(args[0], registerMap);
                if (reg == null) throw new IllegalArgumentException("Invalid register for " + name);
                String[] comps = args[1].split("\\|");
                if (comps.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Invalid vector dimensionality.");
                List<Integer> machineCode = new ArrayList<>();
                machineCode.add(new Symbol(Config.TYPE_DATA, reg).toInt());
                for (String c : comps) {
                    int v = org.evochora.assembler.NumericParser.parseInt(c.strip());
                    machineCode.add(new Symbol(Config.TYPE_DATA, v).toInt());
                }
                return new AssemblerOutput.CodeSequence(machineCode);
            }

            case "POKE":
            case "PEEK": {
                if (args.length != 2) throw new IllegalArgumentException(name + " expects two register arguments.");
                Integer reg1 = resolveRegToken(args[0], registerMap);
                Integer reg2 = resolveRegToken(args[1], registerMap);
                if (reg1 == null || reg2 == null) throw new IllegalArgumentException("Invalid register for " + name);
                return new AssemblerOutput.CodeSequence(List.of(
                        new Symbol(Config.TYPE_DATA, reg1).toInt(),
                        new Symbol(Config.TYPE_DATA, reg2).toInt()
                ));
            }
        }
        throw new IllegalArgumentException("Unknown world interaction instruction for assembler: " + name);
    }
}