package org.evochora.compiler.frontend.lexer;

import org.evochora.compiler.model.token.Token;

import java.util.List;
import java.util.Map;

/**
 * What the Lexer phase found: the placements of the modules, each with the dependencies its
 * file declares, and every file of the compilation as tokens and as lines of text, under its
 * resolved path, the main file included. A file is read once, however often it is placed.
 *
 * @param placements The module placements, each after the placements it imports; the main
 *                   module last.
 * @param tokens     The tokens of every file read, without an end-of-file token, by path.
 * @param lines      The lines of text of every file read, by path, for the source view.
 * @param mainPath   The resolved path of the main file.
 */
public record DependencyGraph(List<ModulePlacement> placements, Map<String, List<Token>> tokens,
                              Map<String, List<String>> lines, String mainPath) {
}
