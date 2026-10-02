package org.evochora.compiler;

import org.evochora.compiler.backend.emit.IEmissionContributor;
import org.evochora.compiler.backend.rewrite.IRewriteRule;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.link.ILinkingDirectiveHandler;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.module.IDependencyInfo;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;
import org.evochora.compiler.frontend.semantics.IDependencySetupHandler;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.postprocess.IPostProcessHandler;
import org.evochora.compiler.frontend.preprocessor.BlockKind;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.frontend.tokenmap.ITokenMapContributor;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ast.AstNode;

/**
 * Pure declarative registration interface for compiler features.
 *
 * <p>Features call these methods during {@link ICompilerFeature#register} to declare
 * which handlers they contribute to each compiler phase. The one thing a feature reads here
 * is the instruction set, which it hands to the handlers that need it.</p>
 *
 * <p>The concrete implementation ({@link FeatureRegistry}) collects all registrations
 * and provides getter methods for the compiler to read them back.</p>
 *
 * <p>Phase 1 (Lexing) is extended through {@link #lexerSymbol(String)}: a feature registers the
 * character sequences it reads, and the lexer emits each of them as one generic
 * {@link org.evochora.compiler.model.token.TokenType#SYMBOL} token whose text is the sequence.</p>
 */
public interface IFeatureRegistrationContext {

	/**
	 * The instruction set this compilation targets, for handlers that read opcodes,
	 * signatures or register banks.
	 *
	 * @return The compiler's view of the instruction set.
	 */
	IInstructionSet isa();

	// Phase 0: Dependency Scanning

	/**
	 * Registers a dependency scan handler for Phase 0.
	 *
	 * @param handler The handler that matches and processes dependency directives.
	 */
	void dependencyScanHandler(IDependencyScanHandler handler);

	/**
	 * Registers a module setup handler for Phase 4 (module relationship setup).
	 *
	 * @param type    The IDependencyInfo subclass this handler processes.
	 * @param handler The handler that sets up module relationships for this dependency type.
	 * @param <T>     The dependency information type produced in Phase 0 that this handler consumes;
	 *                it ties the handler to the class object passed as {@code type}.
	 */
	<T extends IDependencyInfo> void dependencySetupHandler(Class<T> type, IDependencySetupHandler<T> handler);

	// Phase 1: Lexing

	/**
	 * Registers a character sequence the lexer emits as one
	 * {@link org.evochora.compiler.model.token.TokenType#SYMBOL} token with that text. The lexer
	 * tries the registered symbols at the start of every token, longest first, before any of its
	 * own cases, so a symbol may consist only of the characters {@code ! & * + , . / < = > ? @ ^ ~ -};
	 * the single characters {@code .} and {@code -} are taken by the lexer and cannot be registered
	 * alone. The same symbol registered by several features is one symbol.
	 *
	 * @param symbol The character sequence, for example {@code ".."} or {@code "@+"}.
	 * @throws IllegalArgumentException if the symbol is empty, contains a character outside the
	 *         alphabet, or is {@code "."} or {@code "-"}; the message names the symbol.
	 */
	void lexerSymbol(String symbol);

	// Phase 2: Preprocessing

	/**
	 * Registers a preprocessor handler for Phase 2. Handlers registered here are
	 * static (initialization-time) and stored in the immutable registry. Dynamic
	 * runtime registration (e.g., macro expansion handlers) happens through
	 * {@link org.evochora.compiler.frontend.preprocessor.PreProcessorContext#handlers()}.
	 *
	 * @param name    The token text that triggers this handler (e.g., ".MACRO", ".SOURCE").
	 * @param handler The handler that processes matching tokens.
	 */
	void preprocessor(String name, IPreProcessorHandler handler);

	/**
	 * Registers a kind of block for Phase 2: the directives that open, close and divide it, and
	 * whether its body is stored for later or processed in place. The preprocessor matches the
	 * blocks of every registered kind against each other when a handler reads a block, so that
	 * blocks nest and never overlap, whichever feature they belong to. A word may belong to one
	 * kind only.
	 *
	 * @param kind The block kind.
	 */
	void preprocessorBlock(BlockKind kind);

	/**
	 * Registers a directive that may stand only at the top level of the source for Phase 2: never
	 * inside a stored block body and never as a macro argument. A directive may be registered
	 * here without having a preprocessor handler.
	 *
	 * @param directive The directive name, e.g. {@code .SOURCE}.
	 */
	void preprocessorTopLevelOnly(String directive);

	// Phase 3: Parsing

	/**
	 * Registers a parser statement handler for Phase 3.
	 *
	 * @param keyword The keyword that triggers this handler (e.g., ".ORG", ".PROC", "CALL").
	 * @param handler The handler that parses this statement into an AST node.
	 */
	void parserStatement(String keyword, IParserStatementHandler handler);

	/**
	 * Registers the default parser statement handler for Phase 3.
	 * The default handler is invoked for unrecognized keywords (e.g., generic instructions).
	 *
	 * @param handler The default handler.
	 */
	void defaultParserStatement(IParserStatementHandler handler);

	// Phase 4: Semantic Analysis

	/**
	 * Registers a symbol collector for Phase 4 (first pass: symbol collection).
	 *
	 * @param nodeType  The AST node class this collector handles.
	 * @param collector The collector that extracts symbols from matching nodes.
	 */
	void symbolCollector(Class<? extends AstNode> nodeType, ISymbolCollector collector);

	/**
	 * Registers an analysis handler for Phase 4 (second pass: semantic validation).
	 *
	 * @param nodeType The AST node class this handler analyzes.
	 * @param handler  The handler that validates matching nodes.
	 */
	void analysisHandler(Class<? extends AstNode> nodeType, IAnalysisHandler handler);

	// Phase 5: Token Map Generation

	/**
	 * Registers a token map contributor for Phase 5.
	 *
	 * @param nodeType    The AST node class this contributor handles.
	 * @param contributor The contributor that adds token map entries for matching nodes.
	 */
	void tokenMapContributor(Class<? extends AstNode> nodeType, ITokenMapContributor contributor);

	// Phase 6: AST Post-Processing

	/**
	 * Registers a post-process handler for Phase 6.
	 *
	 * @param nodeType The AST node class this handler processes.
	 * @param handler  The handler that collects replacements or constants for matching nodes.
	 */
	void postProcessHandler(Class<? extends AstNode> nodeType, IPostProcessHandler handler);

	// Phase 7: IR Generation

	/**
	 * Registers an IR converter for Phase 7.
	 *
	 * @param nodeType  The AST node class this converter handles.
	 * @param converter The converter that transforms matching nodes into IR.
	 * @param <T>       The specific AST node type.
	 */
	<T extends AstNode> void irConverter(Class<T> nodeType, IAstNodeToIrConverter<T> converter);

	// Phase 8: IR Rewriting (Emission)

	/**
	 * Registers an emission rule for Phase 8.
	 *
	 * @param rule The rule that rewrites IR items (e.g., procedure marshalling).
	 */
	void rewriteRule(IRewriteRule rule);

	// Phase 9: Layout

	/**
	 * Registers a layout directive handler for Phase 9.
	 *
	 * @param namespace The directive namespace (e.g., "core").
	 * @param name      The directive name (e.g., "org", "dir", "place").
	 * @param handler   The handler that processes matching IR directives during layout.
	 */
	void layoutHandler(String namespace, String name, ILayoutDirectiveHandler handler);

	// Phase 10: Linking

	/**
	 * Registers a linking rule for Phase 10.
	 *
	 * @param rule The rule that transforms instructions during linking (e.g., label resolution).
	 */
	void linkingRule(ILinkingRule rule);

	/**
	 * Registers a linking directive handler for Phase 10.
	 *
	 * @param namespace The directive namespace (e.g., "core").
	 * @param name      The directive name (e.g., "push_ctx", "pop_ctx").
	 * @param handler   The handler that processes matching IR directives during linking.
	 */
	void linkingDirectiveHandler(String namespace, String name, ILinkingDirectiveHandler handler);

	// Phase 11: Emission Contributors

	/**
	 * Registers an emission contributor for Phase 11.
	 *
	 * @param contributor The contributor that processes IR directives during emission
	 *                    (e.g., register alias collection, procedure table building).
	 */
	void emissionContributor(IEmissionContributor contributor);
}
