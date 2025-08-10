package org.evochora.organism.instructions;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;

public class SetrInstruction extends Instruction {

    public static final int LENGTH = 3;

    // Reserved ids to encode PR operands in assembler output (value-space of TYPE:DATA)
    private static final int PR_BASE = 1000; // PR0 -> 1000, PR1 -> 1001

    private final int destReg;
    private final int srcReg;

    public SetrInstruction(Organism o, int d, int s, int fullOpcodeId) {
        super(o, fullOpcodeId);
        this.destReg = d;
        this.srcReg = s;
    }

    private Object readOperand(int id) {
        if (id >= PR_BASE) {
            int prIndex = id - PR_BASE;
            return organism.getPr(prIndex);
        } else {
            return organism.getDr(id);
        }
    }

    private boolean writeOperand(int id, Object value) {
        if (id >= PR_BASE) {
            int prIndex = id - PR_BASE;
            return organism.setPr(prIndex, value);
        } else {
            return organism.setDr(id, value);
        }
    }

    @Override
    public void execute(Simulation simulation) {
        Object value = readOperand(srcReg);
        if (value == null) {
            return;
        }
        if (!writeOperand(destReg, value)) {
            organism.instructionFailed("SETR: Failed to set value to target register.");
        }
    }

    public static Instruction plan(Organism organism, World world) {
        int fullOpcodeId = world.getSymbol(organism.getIp()).toInt();
        // Fetch raw argument cells and decode encoded DATA words back to scalar register ids
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int destRaw = result1.value();
        int dest = Symbol.fromInt(destRaw).toScalarValue();

        Organism.FetchResult result2 = organism.fetchArgument(result1.nextIp(), world);
        int srcRaw = result2.value();
        int src = Symbol.fromInt(srcRaw).toScalarValue();

        return new SetrInstruction(organism, dest, src, fullOpcodeId);
    }

    private static Integer resolveRegToken(String token, Map<String, Integer> registerMap) {
        String u = token.toUpperCase();
        // Recognize %DR<n> literal (e.g., %DR0 .. %DR7)
        if (u.startsWith("%DR")) {
            try {
                int idx = Integer.parseInt(u.substring(3));
                return idx;
            } catch (NumberFormatException ignore) {
                // fall through to map/PR checks
            }
        }
        // Recognize %PR0 / %PR1
        if ("%PR0".equals(u)) return PR_BASE + 0;
        if ("%PR1".equals(u)) return PR_BASE + 1;
        // Finally, consult .REG alias map for DR aliases
        Integer id = registerMap.get(u);
        if (id != null) return id;
        return null;
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETR erwartet 2 Argumente: %REG_DEST %REG_SRC");
        Integer destRegId = resolveRegToken(args[0], registerMap);
        if (destRegId == null) throw new IllegalArgumentException("Ungültiges Register-Argument für Ziel: " + args[0]);
        Integer srcRegId = resolveRegToken(args[1], registerMap);
        if (srcRegId == null) throw new IllegalArgumentException("Ungültiges Register-Argument für Quelle: " + args[1]);
        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, destRegId).toInt(),
                new Symbol(Config.TYPE_DATA, srcRegId).toInt()
        ));
    }
}