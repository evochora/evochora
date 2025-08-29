package org.evochora.compiler.api;

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
     * Compiles with explicit world dimensions for vector/label argument sizing.
     * @param sourceLines A list of strings representing the lines of the main source code.
     * @param programName A name for the program, used for debugging purposes.
     * @param worldDimensions The number of dimensions in the target world (e.g., 2 for 2D, 3 for 3D).
     * @return A {@link ProgramArtifact} containing the compiled program and all associated metadata.
     * @throws CompilationException if errors occur during the compilation process.
     */
    ProgramArtifact compile(List<String> sourceLines, String programName, int worldDimensions) throws CompilationException;

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
