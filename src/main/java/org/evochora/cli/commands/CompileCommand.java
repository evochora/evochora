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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

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
 *       a Windows drive letter remains part of the path. Without the option, the directory of the
 *       source file is the only root, so imported modules are resolved relative to it, and no
 *       prefix is defined.</li>
 *   <li>{@code --define} — any number of preprocessor flags, each written as {@code NAME} to set
 *       the flag without a value or as {@code NAME=INTEGER} to set it with that value. The integer
 *       is written as a program writes a number: decimal, or {@code 0x} or {@code 0b} for
 *       hexadecimal or binary, each with an optional leading minus. Names are case-insensitive; a
 *       name given twice, in any case, is an error. Without the option, no flag is set.</li>
 * </ul>
 */
@Command(
    name = "compile",
    description = "Compiles an assembly source file to a ProgramArtifact JSON"
)
public class CompileCommand implements Callable<Integer> {

    /** Digits of a number literal after its sign and radix prefix; the radix checks the rest. */
    private static final Pattern DIGITS = Pattern.compile("[0-9a-fA-F]+");

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

    @Option(
        names = {"--define"},
        paramLabel = "NAME[=INTEGER]",
        description = "Preprocessor flag in format 'NAME' or 'NAME=INTEGER' (e.g., 'REDUNDANCY=2'); repeatable"
    )
    private List<String> defineArgs;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        Instruction.init();

        Map<String, OptionalInt> defines;
        try {
            defines = parseDefines(defineArgs != null ? defineArgs : List.of());
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(), e.getMessage(), e);
        }
        boolean hasSourceRoots = sourceRootArgs != null && !sourceRootArgs.isEmpty();
        // Without source roots the main file's directory is the root, and the main file is named
        // by its absolute path so that it resolves against that root to itself
        String programPath = hasSourceRoots ? file : Path.of(file).toAbsolutePath().normalize().toString();
        CompilerOptions compilerOptions = buildCompilerOptions(programPath, defines);
        EnvironmentProperties envProps = parseEnvironmentProperties(env);

        Compiler compiler = new Compiler();
        try {
            ProgramArtifact artifact = compiler.compile(programPath, envProps, compilerOptions);
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

    /**
     * Builds the options of the compilation: the roots of {@code --source-root}, or without the
     * option a single unprefixed root at the directory of the main file, and the flags.
     *
     * @param programPath The main file; without {@code --source-root} its absolute path.
     * @param defines     The flags of {@code --define}.
     */
    private CompilerOptions buildCompilerOptions(String programPath, Map<String, OptionalInt> defines) {
        if (sourceRootArgs == null || sourceRootArgs.isEmpty()) {
            Path directory = Path.of(programPath).getParent();
            String root = directory != null ? directory.toString() : Path.of("").toAbsolutePath().toString();
            return new CompilerOptions(List.of(new SourceRoot(root, null)), defines);
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
        return new CompilerOptions(roots, defines);
    }

    /**
     * Parses the arguments of {@code --define} into the flags of the compilation.
     *
     * @param defineArgs The arguments, each {@code NAME} or {@code NAME=INTEGER}.
     * @return The flags by name as written, empty for a flag without a value.
     * @throws IllegalArgumentException if a name is empty, a value is not an integer, or a name
     *                                  is given twice, in the same or in another case.
     */
    static Map<String, OptionalInt> parseDefines(List<String> defineArgs) {
        Map<String, OptionalInt> defines = new LinkedHashMap<>();
        Map<String, String> namesByNormalised = new HashMap<>();
        for (String arg : defineArgs) {
            int equalsIdx = arg.indexOf('=');
            String name = (equalsIdx < 0 ? arg : arg.substring(0, equalsIdx)).trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("--define '" + arg + "' has no flag name");
            }
            OptionalInt value = OptionalInt.empty();
            if (equalsIdx >= 0) {
                String text = arg.substring(equalsIdx + 1);
                try {
                    value = OptionalInt.of(parseInteger(text));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("--define " + name + ": '" + text + "' is not an integer", e);
                }
            }
            String previous = namesByNormalised.putIfAbsent(name.toUpperCase(Locale.ROOT), name);
            if (previous != null) {
                throw new IllegalArgumentException("--define " + name + ": the flag is already defined as " + previous
                        + "; flag names are case-insensitive");
            }
            defines.put(name, value);
        }
        return defines;
    }

    /**
     * Reads an integer in one of the forms a program can write a number in: decimal digits, or
     * {@code 0x} or {@code 0b} (either case) followed by hexadecimal or binary digits, each with
     * an optional leading minus.
     *
     * @throws NumberFormatException if the text is no such number or does not fit an {@code int}.
     */
    private static int parseInteger(String text) {
        String s = text.trim();
        boolean negative = s.startsWith("-");
        if (negative) {
            s = s.substring(1);
        }
        int radix = 10;
        if (s.startsWith("0b") || s.startsWith("0B")) {
            radix = 2;
            s = s.substring(2);
        } else if (s.startsWith("0x") || s.startsWith("0X")) {
            radix = 16;
            s = s.substring(2);
        }
        if (!DIGITS.matcher(s).matches()) {
            throw new NumberFormatException("Not a number literal: " + text);
        }
        int value = Integer.parseInt(s, radix);
        return negative ? -value : value;
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
