package org.evochora.cli.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.FlagSetting;
import org.evochora.compiler.api.IntegerLiteral;

import java.io.IOException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.internal.JsonProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.Callable;

/**
 * The {@code compile} subcommand: translates one assembly source file and writes the resulting
 * program artifact in its JSON form as pretty-printed JSON to the command's standard output.
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
 *       a Windows drive letter remains part of the path. Without the option, the directory of the
 *       source file is the only root, so imported modules are resolved relative to it, and no
 *       prefix is defined.</li>
 *   <li>{@code --define} — any number of preprocessor flags, each written as {@code NAME} or
 *       {@code NAME=true} to set the flag without a value, as {@code NAME=NUMBER} to set it with
 *       that value, or as {@code NAME=false} to switch it off. The number is written as a program
 *       writes one: decimal, or {@code 0x}, {@code 0o} or {@code 0b} for hexadecimal, octal or
 *       binary, each with an optional leading minus; {@code true} and {@code false} are
 *       case-insensitive. Names are case-insensitive; a name given twice, in any case, is an
 *       error. Without the option, no flag is named.</li>
 *   <li>{@code --value} — any number of values, each written as {@code NAME=TEXT}, where the text
 *       is an operand as a {@code .CONST} takes it: a literal, a vector or a name. The main
 *       program's {@code .REQUIRE VALUE} of that name takes it in place of its default; a value no
 *       requirement names is ignored. Names are case-insensitive; a name given twice, in any
 *       case, is an error. Without the option, the defaults hold.</li>
 * </ul>
 */
@Command(
    name = "compile",
    description = "Compiles an assembly source file to a ProgramArtifact JSON",
    mixinStandardHelpOptions = true
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
        description = "Environment properties in format 'WIDTHxHEIGHT:topology', topology torus or bound (e.g., '1000x1000:bound'). Default: 1000x1000:torus"
    )
    private String env;

    @Option(
        names = {"--source-root"},
        arity = "0..*",
        description = "Source root directories in format 'path' or 'path:PREFIX' (e.g., './predator:PRED')"
    )
    private List<String> sourceRootArgs;

    @Option(
        names = {"--define"},
        paramLabel = "NAME[=NUMBER|true|false]",
        description = "Preprocessor flag in format 'NAME', 'NAME=NUMBER' (e.g., 'REDUNDANCY=2'), 'NAME=true' or 'NAME=false' to switch it off; repeatable"
    )
    private List<String> defineArgs;

    @Option(
        names = {"--value"},
        paramLabel = "NAME=TEXT",
        description = "Value of the main program's .REQUIRE VALUE in format 'NAME=TEXT' (e.g., 'STEPS=DATA:20'); repeatable"
    )
    private List<String> valueArgs;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        Instruction.init();

        Map<String, FlagSetting> defines;
        Map<String, String> values;
        try {
            defines = parseDefines(defineArgs != null ? defineArgs : List.of());
            values = parseValues(valueArgs != null ? valueArgs : List.of());
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage(), e);
        }
        boolean hasSourceRoots = sourceRootArgs != null && !sourceRootArgs.isEmpty();
        // Without source roots the main file's directory is the root, and the main file is named
        // by its absolute path so that it resolves against that root to itself
        String programPath = hasSourceRoots ? file : Path.of(file).toAbsolutePath().normalize().toString();
        CompilerOptions compilerOptions;
        try {
            compilerOptions = buildCompilerOptions(programPath, defines, values);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage(), e);
        }
        EnvironmentProperties envProps;
        try {
            envProps = parseEnvironmentProperties(env);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage(), e);
        }

        Compiler compiler = new Compiler();
        try {
            ProgramArtifact artifact = compiler.compile(programPath, envProps, compilerOptions);
            JsonProgramArtifact jsonForm = artifact.toJsonForm(envProps);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            PrintWriter out = spec.commandLine().getOut();
            out.println(gson.toJson(jsonForm));

            return 0;
        } catch (CompilationException | IOException e) {
            spec.commandLine().getErr().println(e.getMessage());
            return 1;
        }
    }

    /**
     * Builds the options of the compilation: the roots of {@code --source-root}, or without the
     * option a single unprefixed root at the directory of the main file, the flags and the values.
     *
     * @param programPath The main file; without {@code --source-root} its absolute path.
     * @param defines     The flags of {@code --define}.
     * @param values      The values of {@code --value}.
     * @throws IllegalArgumentException if the options reject the roots, the flags or the values,
     *                                  among them two flag names or two value names that differ
     *                                  only in case.
     */
    private CompilerOptions buildCompilerOptions(String programPath, Map<String, FlagSetting> defines,
                                                 Map<String, String> values) {
        if (sourceRootArgs == null || sourceRootArgs.isEmpty()) {
            Path directory = Path.of(programPath).getParent();
            String root = directory != null ? directory.toString() : Path.of("").toAbsolutePath().toString();
            return new CompilerOptions(List.of(new SourceRoot(root, null)), defines, values);
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
        return new CompilerOptions(roots, defines, values);
    }

    /**
     * Parses the arguments of {@code --define} into the flags of the compilation.
     *
     * @param defineArgs The arguments, each {@code NAME}, {@code NAME=NUMBER}, {@code NAME=true}
     *                   or {@code NAME=false}, with {@code true} and {@code false} in any case.
     * @return The settings of the flags by name as written: set without a value for {@code NAME}
     *         and {@code NAME=true}, set with the value for {@code NAME=NUMBER}, switched off for
     *         {@code NAME=false}.
     * @throws IllegalArgumentException if a name is empty, a value is neither a number nor
     *                                  {@code true} or {@code false}, or a name
     *                                  is given twice as written. Two names that differ only in
     *                                  case are left to {@link CompilerOptions}, which rejects them.
     */
    static Map<String, FlagSetting> parseDefines(List<String> defineArgs) {
        Map<String, FlagSetting> defines = new LinkedHashMap<>();
        for (String arg : defineArgs) {
            int equalsIdx = arg.indexOf('=');
            String name = (equalsIdx < 0 ? arg : arg.substring(0, equalsIdx)).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("--define '" + arg + "' has no flag name");
            }
            FlagSetting setting = equalsIdx < 0 ? FlagSetting.set() : parseSetting(name, arg.substring(equalsIdx + 1));
            if (defines.putIfAbsent(name, setting) != null) {
                throw new IllegalArgumentException("--define " + name + ": the flag is given twice");
            }
        }
        return defines;
    }

    /**
     * Parses the text after the equals sign of one {@code --define} argument.
     *
     * @param name The name of the flag, for the error message.
     * @param text The text after the equals sign.
     * @return Switched off for {@code false}, set without a value for {@code true}, both in any
     *         case, and set with the value for a number.
     * @throws IllegalArgumentException if the text is neither a number nor {@code true} or
     *                                  {@code false}.
     */
    private static FlagSetting parseSetting(String name, String text) {
        String trimmed = text.trim();
        if ("false".equalsIgnoreCase(trimmed)) {
            return FlagSetting.off();
        }
        if ("true".equalsIgnoreCase(trimmed)) {
            return FlagSetting.set();
        }
        OptionalLong value = IntegerLiteral.parse(trimmed);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("--define " + name + ": '" + text + "' is not a number, true or false");
        }
        return FlagSetting.set(value.getAsLong());
    }

    /**
     * Parses the arguments of {@code --value} into the values of the compilation.
     *
     * @param valueArgs The arguments, each {@code NAME=TEXT}.
     * @return The texts by name as written.
     * @throws IllegalArgumentException if an argument has no {@code =}, an empty name or an empty
     *                                  text, or a name is given twice as written. Two names that
     *                                  differ only in case are left to {@link CompilerOptions},
     *                                  which rejects them.
     */
    static Map<String, String> parseValues(List<String> valueArgs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : valueArgs) {
            int equalsIdx = arg.indexOf('=');
            String name = equalsIdx < 0 ? "" : arg.substring(0, equalsIdx).trim();
            String text = equalsIdx < 0 ? "" : arg.substring(equalsIdx + 1).trim();
            if (name.isEmpty() || text.isEmpty()) {
                throw new IllegalArgumentException("--value '" + arg + "' must be NAME=TEXT");
            }
            if (values.putIfAbsent(name, text) != null) {
                throw new IllegalArgumentException("--value " + name + ": the value is given twice");
            }
        }
        return values;
    }

    /**
     * Reads --env: the world's shape, and its topology in the words of the simulation's
     * configuration, {@code torus} (the default, as without --env) or {@code bound}.
     *
     * @throws IllegalArgumentException if a dimension is not a number or the topology is another word
     */
    private EnvironmentProperties parseEnvironmentProperties(String env) {
        if (env == null || env.isEmpty()) {
            return new EnvironmentProperties(new int[]{1000, 1000}, true);
        }

        String[] parts = env.split(":", -1);
        int[] shape;
        try {
            shape = Arrays.stream(parts[0].split("x"))
                    .mapToInt(Integer::parseInt)
                    .toArray();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--env '" + env + "': the shape is not WIDTHxHEIGHT", e);
        }

        String topology = parts.length > 1 ? parts[1] : "torus";
        if (parts.length > 2 || !("torus".equalsIgnoreCase(topology) || "bound".equalsIgnoreCase(topology))) {
            throw new IllegalArgumentException("--env '" + env + "': the topology is torus or bound");
        }
        return new EnvironmentProperties(shape, "torus".equalsIgnoreCase(topology));
    }
}
