package org.evochora.cli.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.CompilerOptions;

import java.io.IOException;
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

/**
 * The {@code compile} subcommand: translates one assembly source file and writes the resulting
 * program artifact in its linearized form as pretty-printed JSON to the command's standard output.
 * A successful run returns exit code 0; a compilation or I/O failure prints its message to the
 * command's error stream and returns exit code 1.
 * <p>
 * The options mean:
 * <ul>
 *   <li>{@code -f}/{@code --file} (required) — the source file to compile, either a path or the
 *       {@code PREFIX:path} form referring to a named source root.</li>
 *   <li>{@code -e}/{@code --env} — the environment the artifact is laid out for, written as the
 *       shape and an optional topology, for example {@code 1000x1000:toroidal}. The shape is a
 *       list of extents in cells, one per dimension, separated by {@code x}. The environment is
 *       toroidal only when the text after the colon is {@code toroidal}, ignoring case; any other
 *       topology, and a shape given without one, yield a non-toroidal environment. If the option
 *       is omitted entirely, a toroidal environment of 1000 by 1000 cells is assumed.</li>
 *   <li>{@code --source-root} — any number of roots against which the source file and its imported
 *       modules are resolved, each written as a plain path or as {@code path:PREFIX} to give the
 *       root a name. A trailing segment counts as a prefix only if it starts with an uppercase
 *       letter and is at least two characters of {@code A-Z}, {@code 0-9} and underscore, so that
 *       a Windows drive letter remains part of the path. Without the option, paths are resolved
 *       relative to the working directory and no prefix is defined.</li>
 * </ul>
 */
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
        try {
            ProgramArtifact artifact = compiler.compile(file, envProps, compilerOptions);
            LinearizedProgramArtifact linearizedArtifact = artifact.toLinearized(envProps);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            PrintWriter out = spec.commandLine().getOut();
            out.println(gson.toJson(linearizedArtifact));

            return 0;
        } catch (CompilationException | IOException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
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
