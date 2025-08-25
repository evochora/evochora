package org.evochora.server.indexer;

import org.evochora.runtime.model.IEnvironmentReader;
import org.evochora.runtime.services.Disassembler;
import org.evochora.runtime.services.DisassemblyData;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Einfacher Performance-Test für den Disassembler.
 * Vergleicht die aktuelle Runtime-Methode (direkt) mit der neuen Disassembler-Methode.
 */
class DisassemblerPerformanceTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * Performance-Test: Vergleicht die aktuelle Runtime-Methode (direkt) 
     * mit der neuen Disassembler-Methode (mit EnvironmentInstruction Objekten).
     */
    @Disabled
    void performanceTest_disassembler_vs_direct() {
        // Test-Setup: 1000 Organismen mit verschiedenen IP-Positionen
        int numOrganisms = 1000;
        int[] worldShape = {100, 100};
        EnvironmentProperties props = new EnvironmentProperties(worldShape, true);
        
        // Mock-Environment für den Test (vereinfacht)
        MockEnvironment mockEnv = new MockEnvironment(props);
        
        // 1. Aktuelle Runtime-Methode (direkt) - simuliert
        long start1 = System.nanoTime();
        for (int i = 0; i < numOrganisms; i++) {
            int[] ip = {i % 100, (i * 7) % 100}; // Verschiedene IP-Positionen
            simulateDirectRuntimeMethod(mockEnv, ip);
        }
        long time1 = System.nanoTime() - start1;
        
        // 2. Neue Disassembler-Methode
        Disassembler disassembler = new Disassembler();
        long start2 = System.nanoTime();
        for (int i = 0; i < numOrganisms; i++) {
            int[] ip = {i % 100, (i * 7) % 100}; // Verschiedene IP-Positionen
            DisassemblyData data = disassembler.disassemble(mockEnv, ip);
            // Simuliere die Verwendung der Instruktion
            if (data != null) {
                String opcodeName = data.opcodeName();
                int numArgs = data.argValues().length;
            }
        }
        long time2 = System.nanoTime() - start2;
        
        // Berechne Overhead
        double overhead = (double)(time2 - time1) / time1 * 100;
        
        System.out.println("=== PERFORMANCE TEST RESULTS ===");
        System.out.println("Direct Runtime Method: " + time1 / 1_000_000.0 + " ms");
        System.out.println("New Disassembler Method: " + time2 / 1_000_000.0 + " ms");
        System.out.println("Overhead: " + String.format("%.2f", overhead) + "%");
        System.out.println("Absolute difference: " + (time2 - time1) / 1_000_000.0 + " ms");
        System.out.println("=================================");
        
        // Assertion: Overhead sollte nicht zu hoch sein
        assertThat(overhead).isLessThan(50.0); // Maximal 50% Overhead akzeptabel
    }
    
    /**
     * Simuliert die aktuelle Runtime-Methode (direkte Interpretation).
     */
    private void simulateDirectRuntimeMethod(MockEnvironment env, int[] ip) {
        try {
            Molecule opcodeMolecule = env.getMolecule(ip);
            if (opcodeMolecule != null) {
                int opcodeId = opcodeMolecule.toInt();
                String opcodeName = Instruction.getInstructionNameById(opcodeId);
                
                // Simuliere Argument-Lesen (vereinfacht)
                if (!"UNKNOWN".equals(opcodeName)) {
                    var signatureOpt = Instruction.getSignatureById(opcodeId);
                    if (signatureOpt.isPresent()) {
                        InstructionSignature sig = signatureOpt.get();
                        for (InstructionArgumentType argType : sig.argumentTypes()) {
                            // Simuliere das Lesen eines Arguments
                            int[] nextPos = env.getProperties().getNextPosition(ip, new int[]{1, 0});
                            Molecule argMolecule = env.getMolecule(nextPos);
                            if (argMolecule != null) {
                                int argValue = argMolecule.toInt();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignoriere Fehler im Test
        }
    }
    
    /**
     * Mock-Environment für den Performance-Test.
     */
    private static class MockEnvironment implements IEnvironmentReader {
        private final EnvironmentProperties properties;
        
        public MockEnvironment(EnvironmentProperties properties) {
            this.properties = properties;
        }
        
        @Override
        public org.evochora.runtime.model.Molecule getMolecule(int[] coordinates) {
            // Vereinfachte Implementierung für den Test
            // In der Realität würde hier die echte Logik stehen
            return new org.evochora.runtime.model.Molecule(42, 0); // Test-Wert (value, type)
        }
        
        @Override
        public int[] getShape() {
            return properties.getWorldShape();
        }
        
        @Override
        public EnvironmentProperties getProperties() {
            return properties;
        }
    }
}
