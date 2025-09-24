package org.evochora.cli;

/**
 * Temporary CLI placeholder after archiving datapipeline and server packages.
 * 
 * This is a minimal implementation to keep the build working while the new
 * datapipeline is being developed from scratch.
 */
public class TemporaryCommandLineInterface {
    
    public static void main(String[] args) {
        System.out.println("Evochora - Temporary CLI");
        System.out.println("========================");
        System.out.println();
        System.out.println("The datapipeline and server packages have been archived.");
        System.out.println("A new datapipeline implementation needs to be created.");
        System.out.println();
        System.out.println("Archived code can be found in: src/archive/2025-09-24/");
        System.out.println();
        System.out.println("Available functionality:");
        System.out.println("- Runtime package: org.evochora.runtime");
        System.out.println("- Compiler package: org.evochora.compiler");
        System.out.println();
        System.out.println("Next steps:");
        System.out.println("1. Create new datapipeline package");
        System.out.println("2. Implement SimulationEngine using runtime's public API");
        System.out.println("3. Replace this temporary CLI");
    }
}
