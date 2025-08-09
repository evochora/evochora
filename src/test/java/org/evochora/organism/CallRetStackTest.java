package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.organism.instructions.CallInstruction;
import org.evochora.organism.instructions.RetInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Deque;

import static org.junit.jupiter.api.Assertions.*;

public class CallRetStackTest {

    private World world;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setup() {
        // Kleine Welt in 2D für Tests
        Config.WORLD_SHAPE[0] = 20;
        Config.WORLD_SHAPE[1] = 5;
        world = new World(Config.WORLD_SHAPE, true);
        simulation = new Simulation(world);
        organism = Organism.create(simulation, new int[]{0, 0}, 2000, simulation.getLogger());
    }

    @Test
    void testReturnStackOverflowTriggersInstructionFailed() {
        // RS vorab auf Max-Tiefe füllen
        Deque<Object> rs = organism.getReturnStack();
        for (int i = 0; i < Config.RS_MAX_DEPTH; i++) {
            rs.push(new int[]{0, 0});
        }

        // CALL am Start platzieren: delta (1,0) ist egal – es soll wegen RS-Overflow fehlschlagen
        int callId = Instruction.getInstructionIdByName("CALL");
        world.setSymbol(new Symbol(Config.TYPE_CODE, callId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 1), 1, 0); // delta.x = 1
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 2, 0); // delta.y = 0

        Instruction call = CallInstruction.plan(organism, world);
        call.execute(simulation);

        assertTrue(organism.isInstructionFailed(), "CALL sollte bei RS-Überlauf fehlschlagen");
    }

    @Test
    void testReturnStackUnderflowTriggersInstructionFailed() {
        // RS ist leer; RET soll fehlschlagen
        int retId = Instruction.getInstructionIdByName("RET");
        world.setSymbol(new Symbol(Config.TYPE_CODE, retId), 0, 0);

        Instruction ret = RetInstruction.plan(organism, world);
        ret.execute(simulation);

        assertTrue(organism.isInstructionFailed(), "RET sollte bei RS-Unterlauf fehlschlagen");
    }

    @Test
    void testCallRetRoundTrip_jumpsAndReturnsToNextAfterCall() {
        // CALL an (0,0) mit delta (5,0); Return-Adresse (nach CALL) liegt bei (3,0), da CALL Länge=3 (1 + 2D)
        int callId = Instruction.getInstructionIdByName("CALL");
        int retId = Instruction.getInstructionIdByName("RET");
        int nopId = Instruction.getInstructionIdByName("NOP");

        // CALL
        world.setSymbol(new Symbol(Config.TYPE_CODE, callId), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_DATA, 5), 1, 0); // delta.x = 5
        world.setSymbol(new Symbol(Config.TYPE_DATA, 0), 2, 0); // delta.y = 0

        // Zieladresse (5,0): RET
        world.setSymbol(new Symbol(Config.TYPE_CODE, retId), 5, 0);

        // Return-Adresse (3,0): NOP (zur Sicherheit nach RET-Rücksprung)
        world.setSymbol(new Symbol(Config.TYPE_CODE, nopId), 3, 0);

        // CALL ausführen
        Instruction call = CallInstruction.plan(organism, world);
        call.execute(simulation);

        // Nach CALL: IP sollte am Ziel stehen und RS eine Adresse enthalten
        int[] ipAfterCall = organism.getIp();
        assertArrayEquals(new int[]{5, 0}, ipAfterCall, "CALL sollte zum Ziel (5,0) springen");
        assertEquals(1, organism.getReturnStack().size(), "RS sollte nach CALL 1 Eintrag haben");
        assertFalse(organism.isInstructionFailed(), "CALL sollte erfolgreich sein");

        // RET am Ziel ausführen
        Instruction ret = RetInstruction.plan(organism, world);
        ret.execute(simulation);
        assertFalse(organism.isInstructionFailed(), "RET sollte erfolgreich sein");

        // Nach RET: IP sollte auf Return-Adresse (nach CALL) stehen, also (3,0)
        int[] ipAfterRet = organism.getIp();
        assertArrayEquals(new int[]{3, 0}, ipAfterRet, "RET sollte zur Adresse nach CALL (3,0) zurückkehren");
        assertEquals(0, organism.getReturnStack().size(), "RS sollte nach RET leer sein");
    }
}
