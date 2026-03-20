package org.evochora.cli.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.internal.LinearizedProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "compile",
    description = "Compiles an assembly source file to a ProgramArtifact JSON"
)
public class CompileCommand implements Callable<Integer> {

    @Option(
        names = {"-f", "--file"},
        required = true,
        description = "Path to the assembly source file (supports PREFIX:path syntax)"
    )
    private String file;

    @Option(
        names = {"-e", "--env"},
        description = "Environment properties in format 'WIDTHxHEIGHT:topology' (e.g., '1000x1000:toroidal'). Default: 1000x1000:toroidal"
    )
    private String env;

    @Option(
        names = {"--source-root"},
        arity = "0..*",
        description = "Source root directories in format 'path' or 'path:PREFIX' (e.g., './predator:PRED')"
    )
    private List<String> sourceRootArgs;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        Instruction.init();

        CompilerOptions compilerOptions = (sourceRootArgs != null && !sourceRootArgs.isEmpty())
                ? buildCompilerOptions()
                : null;
        EnvironmentProperties envProps = parseEnvironmentProperties(env);

        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(file, envProps, compilerOptions);
        LinearizedProgramArtifact linearizedArtifact = artifact.toLinearized(envProps);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        PrintWriter out = spec.commandLine().getOut();
        out.println(gson.toJson(linearizedArtifact));

        return 0;
    }

    private CompilerOptions buildCompilerOptions() {
        if (sourceRootArgs == null || sourceRootArgs.isEmpty()) {
            return CompilerOptions.defaults();
        }
        List<SourceRoot> roots = new ArrayList<>();
        for (String arg : sourceRootArgs) {
            int colonIdx = arg.lastIndexOf(':');
            if (colonIdx > 0 && colonIdx < arg.length() - 1) {
                String candidate = arg.substring(colonIdx + 1);
                // Prefix must be at least 2 chars to avoid collision with Windows drive letters
                if (candidate.matches("[A-Z][A-Z0-9_]+")) {
                    roots.add(new SourceRoot(arg.substring(0, colonIdx), candidate));
                    continue;
                }
            }
            roots.add(new SourceRoot(arg, null));
        }
        return new CompilerOptions(roots);
    }

    private EnvironmentProperties parseEnvironmentProperties(String env) {
        if (env == null || env.isEmpty()) {
            return new EnvironmentProperties(new int[]{1000, 1000}, true);
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
