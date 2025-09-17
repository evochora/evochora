package org.evochora.datapipeline;

import org.evochora.datapipeline.core.CommandLineInterface;

/**
 * The main entry point for the Evochora Data Pipeline application.
 * <p>
 * This class serves as the main entry point for the executable JAR. Its sole
 * responsibility is to delegate control to the {@link CommandLineInterface},
 * which handles all command-line parsing and application logic.
 * </p>
 */
public final class Main {

    /**
     * Private constructor to prevent instantiation.
     */
    private Main() {
        // This class should not be instantiated.
    }

    /**
     * The main method that launches the application.
     *
     * @param args The command-line arguments passed to the application.
     */
    public static void main(final String[] args) {
        CommandLineInterface.main(args);
    }
}
