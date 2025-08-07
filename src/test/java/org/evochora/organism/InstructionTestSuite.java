package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * Eine umfassende Test-Suite, die jede Instruktion des Evochora-Befehlssatzes systematisch überprüft.
 * Methodik: Für jeden Test wird der Maschinencode in die Welt geschrieben und die Simulation
 * einen Tick ausgeführt, um ein realistisches Verhalten sicherzustellen.
 */
public class InstructionTestSuite {

    private World world;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        // Initialisiert den Befehlssatz einmal für alle Tests.
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        // Erstellt vor jedem Test eine saubere Simulationsumgebung.
        world = new World(new int[]{20, 20}, true);
        simulation = new Simulation(world);
        // Der Organismus startet immer bei 5|5, um Torus-Effekte bei negativen Koordinaten zu testen.
        organism = Organism.create(simulation, new int[]{5, 5}, 2000, simulation.getLogger());
        simulation.addOrganism(organism);
    }

    // === Daten & Speicher ===

    @Test
    void testSETI() {
        world.setSymbol(new Symbol(Config.TYPE_CODE, SetiInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5); // %DR0
        world.setSymbol(new Symbol(Config.TYPE_DATA, 123), 7, 5);  // DATA:123
        simulation.tick();
        Assertions.assertEquals(123, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    @Test
    void testSETR() {
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 456).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, SetrInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5); // %DR0
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5); // %DR1
        simulation.tick();
        Assertions.assertEquals(456, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    @Test
    void testSETV() {
        world.setSymbol(new Symbol(Config.TYPE_CODE, SetvInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5); // %DR0
        world.setSymbol(new Symbol(Config.TYPE_DATA, 10), 7, 5); // 10
        world.setSymbol(new Symbol(Config.TYPE_DATA, -20), 8, 5); // -20
        simulation.tick();
        Assertions.assertArrayEquals(new int[]{10, -20}, (int[]) organism.getDr(0));
    }

    @Test
    void testPUSH_and_POP() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 789).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, PushInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        simulation.tick();
        Assertions.assertEquals(1, organism.getDataStack().size());

        organism.setDr(0, 0); // Register leeren
        world.setSymbol(new Symbol(Config.TYPE_CODE, PopInstruction.ID), 7, 5); // Nächster Befehl
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 8, 5);
        simulation.tick();

        Assertions.assertEquals(0, organism.getDataStack().size());
        Assertions.assertEquals(789, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    // === Arithmetik & Logik ===

    @Test
    void testADDR_Scalar() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 22).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, AddrInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);
        simulation.tick();
        Assertions.assertEquals(32, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    @Test
    void testSUBR_Vector() {
        organism.setDr(0, new int[]{10, 20});
        organism.setDr(1, new int[]{3, 8});
        world.setSymbol(new Symbol(Config.TYPE_CODE, SubrInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);
        simulation.tick();
        Assertions.assertArrayEquals(new int[]{7, 12}, (int[]) organism.getDr(0));
    }

    @Test
    void testADDI() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 100).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, AddiInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 50), 7, 5);
        simulation.tick();
        Assertions.assertEquals(150, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    @Test
    void testSUBI() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 100).toInt());
        world.setSymbol(new Symbol(Config.TYPE_CODE, SubiInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 40), 7, 5);
        simulation.tick();
        Assertions.assertEquals(60, Symbol.fromInt((Integer) organism.getDr(0)).value());
    }

    @Test
    void testNADR() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 5).toInt()); // Binär: 0101
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 3).toInt()); // Binär: 0011
        world.setSymbol(new Symbol(Config.TYPE_CODE, NadrInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);
        simulation.tick();
        // 5&3 = 1 (0001). NOT(1) = -2 (in Zweierkomplement)
        Assertions.assertEquals(-2, Symbol.fromInt((Integer) organism.getDr(0)).toScalarValue());
    }

    // === Kontrollfluss ===

    @Test
    void testJMPI() {
        world.setSymbol(new Symbol(Config.TYPE_CODE, JmpiInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 10), 6, 5); // Springe 10 nach rechts
        world.setSymbol(new Symbol(Config.TYPE_DATA, 2), 7, 5);  // und 2 nach unten
        simulation.tick();
        // Start(5,5) + Delta(10,2) = Ziel(15,7)
        Assertions.assertArrayEquals(new int[]{15, 7}, organism.getIp());
    }

    @Test
    void testCALL_and_RET() {
        // CALL nach 10|10 (relativ)
        world.setSymbol(new Symbol(Config.TYPE_CODE, CallInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 5), 6, 5); // Delta-X
        world.setSymbol(new Symbol(Config.TYPE_DATA, 5), 7, 5); // Delta-Y

        // Die Routine bei 10|10 (absolut) enthält nur ein RET
        world.setSymbol(new Symbol(Config.TYPE_CODE, RetInstruction.ID), 10, 10);

        simulation.tick(); // Führt CALL aus, springt zu 10|10
        Assertions.assertArrayEquals(new int[]{10, 10}, organism.getIp());

        simulation.tick(); // Führt RET aus
        // Sollte zurückkehren zur Anweisung NACH dem CALL.
        // CALL ist 3 lang, Start war 5|5, also ist Rücksprungadresse 8|5.
        Assertions.assertArrayEquals(new int[]{8, 5}, organism.getIp());
    }

    // === Bedingungen ===

    @Test
    void testIFR_ConditionFalse() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 10).toInt());
        organism.setDr(1, new Symbol(Config.TYPE_DATA, 20).toInt());

        // IFR %DR0 %DR1 bei 5|5 (Länge 3)
        world.setSymbol(new Symbol(Config.TYPE_CODE, IfrInstruction.ID_IFR), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);

        // NOP bei 8|5 (Länge 1), wird übersprungen
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 8, 5);
        // Nächstes Ziel
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 9, 5);

        simulation.tick();
        Assertions.assertArrayEquals(new int[]{9, 5}, organism.getIp());
    }

    @Test
    void testGTI_ConditionTrue() {
        organism.setDr(0, new Symbol(Config.TYPE_DATA, 100).toInt());

        // GTI %DR0 DATA:50 bei 5|5 (Länge 3)
        world.setSymbol(new Symbol(Config.TYPE_CODE, IfiInstruction.ID_GTI), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 50), 7, 5);

        // NOP bei 8|5, wird NICHT übersprungen
        world.setSymbol(new Symbol(Config.TYPE_CODE, NopInstruction.ID), 8, 5);

        simulation.tick();
        Assertions.assertArrayEquals(new int[]{8, 5}, organism.getIp());
    }

    // === Welt & Zustand ===

    @Test
    void testPEEK() {
        organism.setDp(new int[]{10, 10});
        organism.setDr(1, new int[]{0, 1}); // Vektor nach unten
        world.setSymbol(new Symbol(Config.TYPE_ENERGY, 500), 10, 11); // Energie-Paket

        // PEEK %DR0 %DR1
        world.setSymbol(new Symbol(Config.TYPE_CODE, PeekInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);

        simulation.tick();

        // Energie sollte im Register sein UND der Organismus sie erhalten haben
        Assertions.assertEquals(500, Symbol.fromInt((Integer) organism.getDr(0)).value());
        Assertions.assertEquals(Config.TYPE_ENERGY, Symbol.fromInt((Integer) organism.getDr(0)).type());
        Assertions.assertTrue(organism.getEr() > 2000);
        // Zelle in der Welt sollte jetzt leer sein
        Assertions.assertTrue(world.getSymbol(10, 11).isEmpty());
    }

    @Test
    void testPOKE() {
        organism.setDp(new int[]{10, 10});
        organism.setDr(0, new Symbol(Config.TYPE_STRUCTURE, -1).toInt()); // Ein Stück Mauer
        organism.setDr(1, new int[]{-1, 0}); // Vektor nach links

        // POKE %DR0 %DR1
        world.setSymbol(new Symbol(Config.TYPE_CODE, PokeInstruction.ID), 5, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 6, 5);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 7, 5);

        simulation.tick();

        Symbol s = world.getSymbol(9, 10);
        Assertions.assertEquals(-1, s.value());
        Assertions.assertEquals(Config.TYPE_STRUCTURE, s.type());
    }
}