package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;

public class CallInstruction extends Instruction {

    public static final int ID = 34;
    private final int[] delta;

    public CallInstruction(Organism o, int[] delta) {
        super(o);
        this.delta = delta;
    }

    static {
        Instruction.registerInstruction(CallInstruction.class, ID, "CALL", 1 + Config.WORLD_DIMENSIONS, CallInstruction::plan, CallInstruction::assemble);
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.LABEL, 1, ArgumentType.LABEL));
    }

    @Override
    public String getName() {
        return "CALL";
    }

    @Override
    public int getLength() {
        return 1 + Config.WORLD_DIMENSIONS;
    }

    @Override
    protected int getFixedBaseCost() {
        return 2; // Geringfügig teurer als ein normaler Sprung
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return getFixedBaseCost();
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        if (argIndex >= 0 && argIndex < Config.WORLD_DIMENSIONS) {
            return ArgumentType.COORDINATE;
        }
        throw new IllegalArgumentException("Ungültiger Argumentindex für CALL: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        int[] delta = new int[Config.WORLD_DIMENSIONS];
        int[] currentPos = organism.getIp();

        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            Organism.FetchResult result = organism.fetchSignedArgument(currentPos, world);
            delta[i] = result.value();
            currentPos = result.nextIp();
        }
        return new CallInstruction(organism, delta);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) {
            throw new IllegalArgumentException("CALL erwartet genau 1 Argument (ein Label).");
        }
        // Die Validierung, ob das Label existiert, erfolgt erst im PlaceholderResolver.
        return new AssemblerOutput.JumpInstructionRequest(args[0]);
    }

    @Override
    public void execute(Simulation simulation) {
        // 1. Berechne die absolute Rücksprungadresse in Weltkoordinaten.
        int[] absoluteReturnIp = organism.getIpBeforeFetch();
        for (int i = 0; i < this.getLength(); i++) {
            absoluteReturnIp = organism.getNextInstructionPosition(absoluteReturnIp, simulation.getWorld(), organism.getDvBeforeFetch());
        }

        // 2. KORREKTUR: Konvertiere die absolute Adresse in eine programm-relative Adresse.
        int[] initialPosition = organism.getInitialPosition();
        int[] relativeReturnIp = new int[absoluteReturnIp.length];
        for (int i = 0; i < absoluteReturnIp.length; i++) {
            relativeReturnIp[i] = absoluteReturnIp[i] - initialPosition[i];
        }

        // 3. Schiebe die relative Rücksprungadresse auf den Stack.
        organism.getDataStack().push(relativeReturnIp);

        // 4. Berechne die absolute Zielkoordinate für den Sprung (unverändert).
        int[] targetIp = organism.getTargetCoordinate(organism.getIpBeforeFetch(), this.delta, simulation.getWorld());

        // 5. Setze den IP des Organismus auf die absolute Zielkoordinate.
        organism.setIp(targetIp);
        organism.setSkipIpAdvance(true);
    }
}