package org.evochora.compiler.api;

import org.evochora.runtime.model.EnvironmentProperties;
import java.util.List;

/**
 * Defines the public, clean interface for the Evochora compiler.
 */
public interface ICompiler {

    /**
     * Compiles the given source code.
     *
     * @param sourceLines A list of strings representing the lines of the main source code.
     * @param programName A name for the program, used for debugging purposes.
     * @return A {@link ProgramArtifact} containing the compiled program and all associated metadata.
     * @throws CompilationException if errors occur during the compilation process.
     */
    ProgramArtifact compile(List<String> sourceLines, String programName) throws CompilationException;

    /**
     * Compiles with explicit environment context.
     * @param sourceLines A list of strings representing the lines of the main source code.
     * @param programName A name for the program, used for debugging purposes.
     * @param envProps The environment properties, providing context like world dimensions. Can be null.
     * @return A {@link ProgramArtifact} containing the compiled program and all associated metadata.
     * @throws CompilationException if errors occur during the compilation process.
     */
    ProgramArtifact compile(List<String> sourceLines, String programName, EnvironmentProperties envProps) throws CompilationException;

    /**
     * Sets the verbosity level for log output.
     * @param level The verbosity level (e.g., 0=quiet, 1=normal, 2=verbose, 3=trace).
     */
    void setVerbosity(int level);

    /**
     * Compiles the source code from a file.
     * @param programPath The path to the main source file.
     * @return A {@link ProgramArtifact} containing the compiled program.
     * @throws CompilationException if errors occur during compilation.
     * @throws java.io.IOException if the file cannot be read.
     */
    default ProgramArtifact compile(String programPath) throws CompilationException, java.io.IOException {
        return compile(java.nio.file.Files.readAllLines(java.nio.file.Path.of(programPath)), programPath);
    }
}
