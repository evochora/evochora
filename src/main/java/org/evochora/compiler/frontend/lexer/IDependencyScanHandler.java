package org.evochora.compiler.frontend.lexer;

import org.evochora.compiler.frontend.DirectiveLine;

/**
 * Handler the Lexer phase calls for a line that holds the directive word the handler is
 * registered under. The handler reads the line's tokens, decides what the line means for the
 * files of the compilation, and tells the context what it found: a file to read, a dependency
 * to record, lines to take so that no other handler sees them.
 */
public interface IDependencyScanHandler {

	/**
	 * Handles one line that holds the registered directive word.
	 *
	 * @param line The directive on its line: the word, its operands, and whether something stands
	 *             before it on the line.
	 * @param ctx  The context: the file being read, the placement, the files and the state.
	 */
	void handle(DirectiveLine line, IDependencyScanContext ctx);
}
