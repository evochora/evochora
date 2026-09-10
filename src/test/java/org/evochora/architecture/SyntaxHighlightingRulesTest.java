package org.evochora.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Holds the editor extension's opcode list to the instruction set.
 * <p>
 * The list in {@code evochora.tmLanguage.json} names every instruction a second time, and nothing
 * in the build reads it, so an instruction added to the instruction set stays unhighlighted until
 * someone notices — which is how the list came to be missing 47 of them. This rule makes the
 * duplication visible at build time instead of in an editor.
 * <p>
 * Only the instruction names are checked. Directives and register banks are named in the same file
 * and have no registry to compare against; they are read and corrected by hand.
 */
@Tag("unit")
class SyntaxHighlightingRulesTest {

    private static final Path GRAMMAR =
            Path.of("extensions/vscode/src/extension/syntaxes/evochora.tmLanguage.json");

    private static Set<String> highlightedNames;

    @BeforeAll
    static void readGrammar() throws IOException {
        Instruction.init();
        String grammar = Files.readString(GRAMMAR);
        Matcher opcodes = Pattern.compile("\"match\": \"\\(\\?i\\)\\\\\\\\b\\(([A-Z0-9|]+)\\)")
                .matcher(grammar);
        assertThat(opcodes.find())
                .as("opcode pattern in %s", GRAMMAR)
                .isTrue();
        highlightedNames = new TreeSet<>(Set.of(opcodes.group(1).split("\\|")));
    }

    @Test
    void everyInstructionIsHighlighted() {
        Set<String> registered = new TreeSet<>(Instruction.getAllInstructions().values());
        Set<String> missing = registered.stream()
                .filter(name -> !highlightedNames.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(missing)
                .as("instructions the editor extension does not highlight")
                .isEmpty();
    }

    @Test
    void nothingIsHighlightedThatIsNotAnInstruction() {
        Set<String> registered = new TreeSet<>(Instruction.getAllInstructions().values());
        Set<String> unknown = highlightedNames.stream()
                .filter(name -> !registered.contains(name))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(unknown)
                .as("names the editor extension highlights as instructions but the instruction set does not know")
                .isEmpty();
    }
}
