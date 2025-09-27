package org.evochora.datapipeline.cli.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "compile", description = "Compiles an assembly file to a ProgramArtifact JSON.")
public class CompileCommand implements Callable<Integer> {

    @Option(names = {"-f", "--file"}, required = true, description = "The path to the assembly file.")
    private File file;

    @Option(names = {"-e", "--env"}, description = "The environment properties, e.g., '100x100:toroidal'.")
    private String env;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        Instruction.init(); // Initialize the instruction set
        List<String> sourceLines = Files.readAllLines(file.toPath());
        EnvironmentProperties envProps = parseEnvironmentProperties(env);

        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(sourceLines, file.getName(), envProps);
        LinearizedProgramArtifact linearizedArtifact = artifact.toLinearized(envProps);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        PrintWriter out = spec.commandLine().getOut();
        out.println(gson.toJson(linearizedArtifact));

        return 0;
    }

    private EnvironmentProperties parseEnvironmentProperties(String env) {
        if (env == null || env.isEmpty()) {
            return new EnvironmentProperties(new int[]{100, 100}, true); // Default
        }

        String[] parts = env.split(":");
        String[] dimensions = parts[0].split("x");
        int[] shape = Arrays.stream(dimensions)
                .mapToInt(Integer::parseInt)
                .toArray();

        boolean toroidal = parts.length > 1 && "toroidal".equalsIgnoreCase(parts[1]);

        return new EnvironmentProperties(shape, toroidal);
    }
}