// src/main/java/org/evochora/organism/IftiInstruction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.assembler.ArgumentType;
import org.evochora.assembler.AssemblerOutput;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class IftiInstruction extends Instruction {

    public static final int ID = 29; // Wähle eine neue, unbenutzte ID

    private final int reg1;
    private final int literalValue;

    public IftiInstruction(Organism o, int r1, int literal) {
        super(o);
        this.reg1 = r1;
        this.literalValue = literal;
    }

    static {
        // Registriere die neue Anweisung
        Instruction.registerInstruction(IftiInstruction.class, ID, "IFTI", 3, IftiInstruction::plan, IftiInstruction::assemble);

        // Registriere die Argumenttypen
        Instruction.registerArgumentTypes(ID, Map.of(0, ArgumentType.REGISTER, 1, ArgumentType.LITERAL));
    }

    @Override
    public String getName() {
        return "IFTI";
    }

    @Override
    public int getLength() {
        return 3;
    }

    @Override
    protected int getFixedBaseCost() {
        return 1;
    }

    @Override
    public int getCost(Organism organism, World world, List<Integer> rawArguments) {
        return getFixedBaseCost();
    }

    @Override
    public ArgumentType getArgumentType(int argIndex) {
        if (argIndex == 0) return ArgumentType.REGISTER;
        if (argIndex == 1) return ArgumentType.LITERAL;
        throw new IllegalArgumentException("Ungültiger Argumentindex für IFTI: " + argIndex);
    }

    public static Instruction plan(Organism organism, World world) {
        // Lese das erste Argument (Register-Index)
        Organism.FetchResult result1 = organism.fetchArgument(organism.getIp(), world);
        int r1 = result1.value();

        // Berechne die Position des ZWEITEN Arguments
        int[] firstArgIp = result1.nextIp();
        int[] secondArgIp = organism.getNextInstructionPosition(firstArgIp, world, organism.getDvBeforeFetch());

        // Lese den Literal-Wert von der Position des ZWEITEN Arguments
        int literal = world.getSymbol(secondArgIp).toInt();

        return new IftiInstruction(organism, r1, literal);
    }

    public static AssemblerOutput assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("IFTI erwartet 2 Argumente: %REG TYPE:WERT");

        Integer regId = registerMap.get(args[0].toUpperCase());
        if (regId == null) {
            throw new IllegalArgumentException("Ungültiges Register-Argument: " + args[0]);
        }

        String[] literalParts = args[1].split(":");
        if (literalParts.length != 2) {
            throw new IllegalArgumentException("Literal muss das Format TYPE:WERT haben: " + args[1]);
        }
        String typeName = literalParts[0].toUpperCase();
        int value = Integer.parseInt(literalParts[1]);
        int type = switch (typeName) {
            case "CODE" -> Config.TYPE_CODE;
            case "DATA" -> Config.TYPE_DATA;
            case "ENERGY" -> Config.TYPE_ENERGY;
            case "STRUCTURE" -> Config.TYPE_STRUCTURE;
            default -> throw new IllegalArgumentException("Unbekannter Typ für Literal: " + typeName);
        };

        int fullLiteralValue = new Symbol(type, value).toInt();

        return new AssemblerOutput.CodeSequence(List.of(
                new Symbol(Config.TYPE_DATA, regId).toInt(),
                fullLiteralValue
        ));
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1Obj = organism.getDr(reg1);

        if (val1Obj == null) {
            // Null-Werte können nicht mit einem Typ verglichen werden, also ist die Bedingung nicht erfüllt.
            organism.skipNextInstruction(organism.getSimulation().getWorld());
            return;
        }

        // Die Typ-ID des Registers extrahieren
        int registerType = 0;
        if (val1Obj instanceof Integer v1Raw) {
            registerType = Symbol.fromInt(v1Raw).type();
        } else if (val1Obj instanceof int[]) {
            // Vektoren haben keinen expliziten Typ in der Welt, aber sie können einen Platzhalter-Typ bekommen.
            // In dieser Implementierung wird der Typ des Symbols verglichen, nicht der des Objekts.
            // Der Typ des Symbols, das den Registerindex darstellt, ist immer TYPE_DATA.
            // Wir müssen hier den Typ des Wertes im Register (Integer oder Vektor) von der Logik trennen.
            // Da ein Vektor keine Symbol-Repräsentation hat, wird die Bedingung nicht erfüllt.
            organism.skipNextInstruction(organism.getSimulation().getWorld());
            return;
        }

        // Die Typ-ID des Literals extrahieren
        int literalType = Symbol.fromInt(this.literalValue).type();

        if (registerType == literalType) {
            // Die Bedingung ist erfüllt.
        } else {
            // Die Bedingung ist nicht erfüllt.
            organism.skipNextInstruction(organism.getSimulation().getWorld());
        }
    }
}