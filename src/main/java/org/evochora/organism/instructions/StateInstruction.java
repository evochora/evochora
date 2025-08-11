package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class StateInstruction extends Instruction {

    public StateInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(Simulation simulation) {
        String opName = getName();
        List<Operand> operands = resolveOperands(simulation.getWorld());

        try {
            switch (opName) {
                case "TURN": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for TURN."); return; }
                    int[] newDv = (int[]) operands.get(0).value();
                    if (organism.isUnitVector(newDv)) {
                        organism.setDv(newDv);
                    }
                    break;
                }
                case "SYNC": {
                    organism.setDp(organism.getIpBeforeFetch());
                    break;
                }
                case "NRG": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for NRG."); return; }
                    int targetReg = operands.get(0).rawSourceId();
                    writeOperand(targetReg, new Symbol(Config.TYPE_DATA, organism.getEr()).toInt());
                    break;
                }
                case "NRGS": {
                    // Stack variant: push ER to data stack, no operands
                    organism.getDataStack().push(new Symbol(Config.TYPE_DATA, organism.getEr()).toInt());
                    break;
                }
                case "FORK": {
                    if (operands.size() != 3) { organism.instructionFailed("Invalid operands for FORK."); return; }
                    int[] delta = (int[]) operands.get(0).value();
                    int energy = Symbol.fromInt((Integer) operands.get(1).value()).toScalarValue();
                    int[] childDv = (int[]) operands.get(2).value();
                    int totalCost = getCost(organism, simulation.getWorld(), null); // Cost is dynamic
                    if (energy > 0 && organism.getEr() >= totalCost) {
                        int[] childIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), delta, simulation.getWorld());
                        organism.takeEr(totalCost);
                        Organism child = Organism.create(simulation, childIp, energy, organism.getLogger());
                        child.setDv(childDv);
                        simulation.addNewOrganism(child);
                    } else {
                        organism.instructionFailed("FORK failed due to insufficient energy or invalid parameters.");
                    }
                    break;
                }
                case "DIFF": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for DIFF."); return; }
                    int[] ip = organism.getIp();
                    int[] dp = organism.getDp();
                    int[] delta = new int[ip.length];
                    for (int i = 0; i < ip.length; i++) {
                        delta[i] = dp[i] - ip[i];
                    }
                    writeOperand(operands.get(0).rawSourceId(), delta);
                    break;
                }
                case "POS": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for POS."); return; }
                    int[] currentIp = organism.getIp();
                    int[] initialPosition = organism.getInitialPosition();
                    int[] delta = new int[currentIp.length];
                    for (int i = 0; i < currentIp.length; i++) {
                        delta[i] = currentIp[i] - initialPosition[i];
                    }
                    writeOperand(operands.get(0).rawSourceId(), delta);
                    break;
                }
                case "RAND": {
                    if (operands.size() != 1) { organism.instructionFailed("Invalid operands for RAND."); return; }
                    Operand op = operands.get(0);
                    Symbol s = Symbol.fromInt((Integer)op.value());
                    int upperBound = s.toScalarValue();
                    if (upperBound <= 0) {
                        organism.instructionFailed("RAND upper bound must be > 0.");
                        writeOperand(op.rawSourceId(), new Symbol(s.type(), 0).toInt());
                        return;
                    }
                    Random random = organism.getRandom();
                    int randomValue = random.nextInt(upperBound);
                    writeOperand(op.rawSourceId(), new Symbol(s.type(), randomValue).toInt());
                    break;
                }
                case "SEEK", "SEKI", "SEKS": {
                    if (operands.size() != 1) {
                        organism.instructionFailed("Invalid operands for " + opName);
                        return;
                    }
                    int[] vector = (int[]) operands.get(0).value();
                    int[] targetCoordinate = organism.getTargetCoordinate(organism.getDp(), vector, simulation.getWorld());

                    if (simulation.getWorld().getSymbol(targetCoordinate).isEmpty()) {
                        organism.setDp(targetCoordinate);
                    } else {
                        organism.instructionFailed("SEEK: Target cell is not empty.");
                    }
                    break;
                }
                case "PEEK", "PEKI", "PEKS": {
                    int targetReg;
                    int[] vector;
                    if (opName.endsWith("S")) {
                        if (operands.size() != 1) { organism.instructionFailed("Invalid operands for " + opName); return; }
                        vector = (int[]) operands.get(0).value();
                        targetReg = -1;
                    } else {
                        if (operands.size() != 2) { organism.instructionFailed("Invalid operands for " + opName); return; }
                        targetReg = operands.get(0).rawSourceId();
                        vector = (int[]) operands.get(1).value();
                    }
                    World world = simulation.getWorld();
                    int[] target = organism.getTargetCoordinate(organism.getDp(), vector, world);
                    Symbol s = world.getSymbol(target);

                    if (s.isEmpty()) {
                        organism.instructionFailed("PEEK: Target cell is empty.");
                        return;
                    }

                    Object valueToStore;
                    if (s.type() == Config.TYPE_ENERGY) {
                        // Award energy when peeking ENERGY
                        int energyToTake = Math.min(s.toScalarValue(), Config.MAX_ORGANISM_ENERGY - organism.getEr());
                        organism.addEr(energyToTake);
                        valueToStore = new Symbol(Config.TYPE_ENERGY, energyToTake).toInt();
                    } else {
                        // Charge energy equal to absolute value of the payload for non-ENERGY types
                        int cost = Math.abs(s.toScalarValue());
                        if (cost > 0) organism.takeEr(cost);
                        valueToStore = s.toInt();
                    }

                    if (opName.endsWith("S")) {
                        organism.getDataStack().push(valueToStore);
                    } else {
                        writeOperand(targetReg, valueToStore);
                    }

                    // Destructive read: clear the cell
                    world.setSymbol(new Symbol(Config.TYPE_CODE, 0), target);
                    break;
                }
                case "SCAN", "SCNI", "SCNS": {
                    int targetReg;
                    int[] vector;
                    if (opName.endsWith("S")) {
                        if (operands.size() != 1) { organism.instructionFailed("Invalid operands for " + opName); return; }
                        vector = (int[]) operands.get(0).value();
                        targetReg = -1;
                    } else {
                        if (operands.size() != 2) { organism.instructionFailed("Invalid operands for " + opName); return; }
                        targetReg = operands.get(0).rawSourceId();
                        vector = (int[]) operands.get(1).value();
                    }
                    World world = simulation.getWorld();
                    int[] target = organism.getTargetCoordinate(organism.getDp(), vector, world);
                    Symbol s = world.getSymbol(target);
                    if (opName.endsWith("S")) {
                        organism.getDataStack().push(s.toInt());
                    } else {
                        writeOperand(targetReg, s.toInt());
                    }
                    break;
                }
                default:
                    organism.instructionFailed("Unknown state instruction: " + opName);
            }
        } catch (ClassCastException | ArrayIndexOutOfBoundsException e) {
            organism.instructionFailed("Invalid operand types for state instruction.");
        }
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        if (getName().equals("FORK")) {
            // Cost for FORK is dynamic based on energy given to child
            List<Operand> operands = resolveOperands(world);
            if (operands.size() == 3 && operands.get(1).value() instanceof Integer) {
                return 10 + Symbol.fromInt((Integer)operands.get(1).value()).toScalarValue();
            }
            return 10; // Default cost if something goes wrong
        }
        return super.getCost(organism, world, rawArguments);
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        return new StateInstruction(organism, fullOpcodeId);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap, String instructionName) {
        String name = instructionName.toUpperCase();
        switch (name) {
            case "SYNC":
                if (args.length != 0) throw new IllegalArgumentException("SYNC expects no arguments.");
                return new AssemblerOutput.CodeSequence(List.of());
            case "NRGS":
                if (args.length != 0) throw new IllegalArgumentException("NRGS expects no arguments.");
                return new AssemblerOutput.CodeSequence(List.of());
            case "TURN", "NRG", "DIFF", "POS", "RAND", "SEEK": {
                if (args.length != 1) throw new IllegalArgumentException(name + " expects 1 register argument.");
                Integer reg = resolveRegToken(args[0], registerMap);
                if (reg == null) throw new IllegalArgumentException("Invalid register for " + name);
                return new AssemblerOutput.CodeSequence(List.of(new Symbol(Config.TYPE_DATA, reg).toInt()));
            }
            case "FORK": {
                if (args.length != 3) throw new IllegalArgumentException("FORK expects 3 register arguments.");
                Integer reg1 = resolveRegToken(args[0], registerMap);
                Integer reg2 = resolveRegToken(args[1], registerMap);
                Integer reg3 = resolveRegToken(args[2], registerMap);
                if (reg1 == null || reg2 == null || reg3 == null) throw new IllegalArgumentException("Invalid register for FORK.");
                return new AssemblerOutput.CodeSequence(List.of(
                        new Symbol(Config.TYPE_DATA, reg1).toInt(),
                        new Symbol(Config.TYPE_DATA, reg2).toInt(),
                        new Symbol(Config.TYPE_DATA, reg3).toInt()
                ));
            }
            case "SEKI": {
                if (args.length != 1) throw new IllegalArgumentException("SEKI expects 1 vector argument.");
                String[] comps = args[0].split("\\|");
                if (comps.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("Invalid vector dimensionality for SEKI");
                List<Integer> machineCode = new ArrayList<>();
                for (String c : comps) {
                    int v = org.evochora.assembler.NumericParser.parseInt(c.strip());
                    machineCode.add(new Symbol(Config.TYPE_DATA, v).toInt());
                }
                return new AssemblerOutput.CodeSequence(machineCode);
            }
            case "SEKS": {
                if (args.length != 0) throw new IllegalArgumentException("SEKS expects no arguments.");
                return new AssemblerOutput.CodeSequence(List.of());
            }
            // PEEK/SCAN gehören zur StateInstruction
            case "PEEK":
            case "SCAN": {
                if (args.length != 2) throw new IllegalArgumentException(name + " expects two register arguments.");
                Integer reg1 = resolveRegToken(args[0], registerMap);
                Integer reg2 = resolveRegToken(args[1], registerMap);
                if (reg1 == null || reg2 == null) throw new IllegalArgumentException("Invalid register for " + name);
                return new AssemblerOutput.CodeSequence(List.of(
                        new Symbol(Config.TYPE_DATA, reg1).toInt(),
                        new Symbol(Config.TYPE_DATA, reg2).toInt()
                ));
            }
            case "PEKI":
            case "SCNI": {
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
            case "PEKS":
            case "SCNS": {
                if (args.length != 0) throw new IllegalArgumentException(name + " expects no arguments.");
                return new AssemblerOutput.CodeSequence(List.of());
            }
        }
        throw new IllegalArgumentException("Cannot assemble unknown state instruction: " + name);
    }
}