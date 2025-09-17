package org.evochora.datapipeline.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "compile",
        mixinStandardHelpOptions = true,
        description = "Compiles an Evochora assembly file to a JSON ProgramArtifact.")
public class CompileCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CompileCommand.class);

    @Parameters(index = "0", description = "The assembly file to compile.")
    private File file;

    @Option(names = "--env", description = "Environment properties (e.g., 1000x1000:toroidal).")
    private String env;

    @Override
    public Integer call() throws Exception {
        try {
            if (!file.exists()) {
                log.error("Error: File not found: {}", file.getAbsolutePath());
                return 2;
            }

            List<String> sourceLines = Files.readAllLines(file.toPath());
            Instruction.init();

            EnvironmentProperties envProps = parseEnvironmentProperties(env);

            Compiler compiler = new Compiler();
            ProgramArtifact artifact = compiler.compile(sourceLines, file.getAbsolutePath(), envProps);
            LinearizedProgramArtifact linearized = artifact.toLinearized(envProps);

            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(linearized);

            System.out.println(json);
            return 0;
        } catch (CompilationException e) {
            log.error("Compilation error: {}", e.getMessage());
            return 1;
        } catch (Exception e) {
            log.error("An unexpected error occurred: {}", e.getMessage(), e);
            return 2;
        }
    }

    private EnvironmentProperties parseEnvironmentProperties(String envSpec) {
        if (envSpec == null) {
            return new EnvironmentProperties(new int[]{1000, 1000}, true);
        }

        try {
            String[] parts = envSpec.split(":");
            String[] dimParts = parts[0].split("x");
            int[] dims = new int[dimParts.length];
            for (int i = 0; i < dimParts.length; i++) {
                dims[i] = Integer.parseInt(dimParts[i]);
            }

            boolean toroidal = true;
            if (parts.length > 1 && parts[1].equalsIgnoreCase("flat")) {
                toroidal = false;
            }

            return new EnvironmentProperties(dims, toroidal);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid environment specification: " + envSpec, e);
        }
    }
}
