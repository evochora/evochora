package org.evochora.compiler;

import org.evochora.compiler.backend.emit.IEmissionContributor;
import org.evochora.compiler.backend.rewrite.IRewriteRule;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.link.ILinkingDirectiveHandler;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.lexer.IDependencyInfo;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;
import org.evochora.compiler.frontend.lexer.Tokenizer;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.postprocess.IPostProcessHandler;
import org.evochora.compiler.frontend.BlockKind;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorBlockHandler;
import org.evochora.compiler.frontend.parser.IParserBlockHandler;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.semantics.IDependencySetupHandler;
import org.evochora.compiler.frontend.semantics.IAnalysisHandler;
import org.evochora.compiler.frontend.semantics.ISymbolCollector;
import org.evochora.compiler.frontend.tokenmap.ITokenMapContributor;
import org.evochora.compiler.expression.IOperation;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.isa.IInstructionSet;
import org.evochora.compiler.model.ast.AstNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Collects all handler registrations from {@link ICompilerFeature} implementations.
 *
 * <p>Features call the {@link IFeatureRegistrationContext} methods (inherited from the interface)
 * to register their handlers. The compiler then reads the collected handlers via the getter
 * methods on this class to populate phase registries.</p>
 *
 * <p>The getter methods are intentionally NOT on the {@link IFeatureRegistrationContext} interface —
 * features only see the write-only registration side. The compiler sees the full class with getters.</p>
 */
public class FeatureRegistry implements IFeatureRegistrationContext {

	/**
	 * The characters a lexer symbol may consist of. The lexer tries symbols before identifiers,
	 * numbers, strings, comments and registers, so letters, digits, {@code _}, {@code $}, {@code #},
	 * {@code "}, {@code ;}, {@code %} and whitespace would cut into those. The tokenizer reads
	 * {@code |}, {@code :}, {@code ,} and the parentheses and brackets as tokens of its own, and
	 * {@code @} is a name of its own; {@code \} and {@code §} are kept out as a reserve for the
	 * core. A character joins when a feature needs it and the core does not claim it.
	 */
	private static final String LEXER_SYMBOL_ALPHABET = "!&*+./<=>?^~-";

	private final IInstructionSet isa;

	// The operations of computations; the registry checks each registration itself.
	private final OperationRegistry operations = new OperationRegistry();

	// Map-based registrations are key-based with guardDuplicate — duplicates are a configuration error.
	private final Map<Class<? extends IDependencyInfo>, IDependencySetupHandler<?>> dependencySetupHandlers = new HashMap<>();
	private final Map<String, IPreProcessorHandler> preprocessorHandlers = new HashMap<>();
	private final Map<String, IParserStatementHandler> parserStatementHandlers = new HashMap<>();
	private IParserStatementHandler defaultParserStatementHandler;
	private final Map<Class<? extends AstNode>, ISymbolCollector> symbolCollectors = new HashMap<>();
	private final Map<Class<? extends AstNode>, IAnalysisHandler> analysisHandlers = new HashMap<>();
	private final Map<Class<? extends AstNode>, ITokenMapContributor> tokenMapContributors = new HashMap<>();
	private final Map<Class<? extends AstNode>, IPostProcessHandler> postProcessHandlers = new HashMap<>();
	private final Map<Class<? extends AstNode>, IAstNodeToIrConverter<?>> irConverters = new HashMap<>();
	private final Map<String, ILayoutDirectiveHandler> layoutHandlers = new HashMap<>();
	private final Map<String, ILinkingDirectiveHandler> linkingDirectiveHandlers = new HashMap<>();

	// The scan handlers of the Lexer phase, by the directive word each one reads; one per word
	private final Map<String, IDependencyScanHandler> dependencyScanHandlers = new LinkedHashMap<>();
	private final List<IRewriteRule> rewriteRules = new ArrayList<>();
	private final List<ILinkingRule> linkingRules = new ArrayList<>();
	private final List<IEmissionContributor> emissionContributors = new ArrayList<>();

	// A block kind is checked for conflicting words when the compiler fills the preprocessor's
	// registry; a top-level-only directive registered twice is one directive.
	private final List<PreprocessorBlock> preprocessorBlocks = new ArrayList<>();
	private final List<ParserBlock> parserBlocks = new ArrayList<>();
	private final Set<String> preprocessorTopLevelOnly = new LinkedHashSet<>();

	// A symbol registered by two features is one symbol, so repeated registration is no conflict.
	private final Set<String> lexerSymbols = new LinkedHashSet<>();

	/**
	 * Creates an empty registry for a compilation that targets the given instruction set.
	 *
	 * @param isa The instruction set the features' handlers read.
	 */
	public FeatureRegistry(IInstructionSet isa) {
		this.isa = isa;
	}

	/**
	 * Checks that every feature of a compiler finds the features it requires among them.
	 *
	 * @param features The features of a compiler.
	 * @throws IllegalArgumentException if a feature requires a feature that is not among them.
	 */
	public static void checkRequirements(List<ICompilerFeature> features) {
		Set<Class<?>> present = new HashSet<>();
		features.forEach(feature -> present.add(feature.getClass()));
		for (ICompilerFeature feature : features) {
			for (Class<? extends ICompilerFeature> required : feature.requires()) {
				if (!present.contains(required)) {
					throw new IllegalArgumentException("Feature '" + feature.name() + "' requires the feature "
							+ required.getSimpleName() + ", which is not among the compiler's features.");
				}
			}
		}
	}

	/**
	 * Checks what can be checked only once every feature has registered: no operation of
	 * computations is named like a word the preprocessor acts on, a directive of its handlers or a
	 * word of its block kinds.
	 *
	 * @throws IllegalStateException if an operation is named like such a word.
	 */
	public void validate() {
		Set<String> words = new LinkedHashSet<>(preprocessorHandlers.keySet());
		preprocessorBlocks.forEach(block -> words.addAll(block.kind().words()));
		operations.validate(words);
	}

	// --- IFeatureRegistrationContext implementation (write side) ---

	@Override
	public IInstructionSet isa() {
		return isa;
	}

	@Override
	public void operation(IOperation operation) {
		operations.register(operation);
	}

	@Override
	public OperationRegistry operations() {
		return operations;
	}

	@Override
	public void lexerSymbol(String symbol) {
		if (symbol.isEmpty()) {
			throw new IllegalArgumentException("Lexer symbol '' is empty; a symbol needs at least one character.");
		}
		for (int i = 0; i < symbol.length(); i++) {
			char c = symbol.charAt(i);
			if (LEXER_SYMBOL_ALPHABET.indexOf(c) < 0) {
				throw new IllegalArgumentException("Lexer symbol '" + symbol + "' contains '" + c
						+ "', which is not one of " + LEXER_SYMBOL_ALPHABET + ".");
			}
		}
		if (symbol.length() == 1 && Tokenizer.FIXED_CHARACTERS.indexOf(symbol.charAt(0)) >= 0) {
			throw new IllegalArgumentException("Lexer symbol '" + symbol
					+ "' is a fixed case of the lexer and cannot be registered alone.");
		}
		lexerSymbols.add(symbol);
	}

	@Override
	public void dependencyScanHandler(String directive, IDependencyScanHandler handler) {
		String word = directive.toUpperCase(Locale.ROOT);
		guardDuplicate(dependencyScanHandlers, word, "dependency scan handler");
		dependencyScanHandlers.put(word, handler);
	}

	@Override
	public <T extends IDependencyInfo> void dependencySetupHandler(
			Class<T> type, IDependencySetupHandler<T> handler) {
		guardDuplicate(dependencySetupHandlers, type, "dependency setup handler");
		dependencySetupHandlers.put(type, handler);
	}

	@Override
	public void preprocessor(String name, IPreProcessorHandler handler) {
		String key = name.toUpperCase(Locale.ROOT);
		guardDuplicate(preprocessorHandlers, key, "preprocessor handler");
		preprocessorHandlers.put(key, handler);
	}

	@Override
	public void preprocessorBlock(BlockKind kind, IPreProcessorBlockHandler handler, boolean stored) {
		preprocessorBlocks.add(new PreprocessorBlock(kind, handler, stored));
	}

	@Override
	public void preprocessorTopLevelOnly(String directive) {
		preprocessorTopLevelOnly.add(directive.toUpperCase(Locale.ROOT));
	}

	@Override
	public void parserStatement(String keyword, IParserStatementHandler handler) {
		String key = keyword.toUpperCase(Locale.ROOT);
		guardDuplicate(parserStatementHandlers, key, "parser statement handler");
		parserStatementHandlers.put(key, handler);
	}

	@Override
	public void parserBlock(BlockKind kind, IParserBlockHandler handler) {
		parserBlocks.add(new ParserBlock(kind, handler));
	}

	@Override
	public void defaultParserStatement(IParserStatementHandler handler) {
		if (this.defaultParserStatementHandler != null) {
			throw new IllegalStateException("Default parser statement handler already registered");
		}
		this.defaultParserStatementHandler = handler;
	}

	@Override
	public void symbolCollector(Class<? extends AstNode> nodeType, ISymbolCollector collector) {
		guardDuplicate(symbolCollectors, nodeType, "symbol collector");
		symbolCollectors.put(nodeType, collector);
	}

	@Override
	public void analysisHandler(Class<? extends AstNode> nodeType, IAnalysisHandler handler) {
		guardDuplicate(analysisHandlers, nodeType, "analysis handler");
		analysisHandlers.put(nodeType, handler);
	}

	@Override
	public void tokenMapContributor(Class<? extends AstNode> nodeType, ITokenMapContributor contributor) {
		guardDuplicate(tokenMapContributors, nodeType, "token map contributor");
		tokenMapContributors.put(nodeType, contributor);
	}

	@Override
	public void postProcessHandler(Class<? extends AstNode> nodeType, IPostProcessHandler handler) {
		guardDuplicate(postProcessHandlers, nodeType, "post-process handler");
		postProcessHandlers.put(nodeType, handler);
	}

	@Override
	public <T extends AstNode> void irConverter(Class<T> nodeType, IAstNodeToIrConverter<T> converter) {
		guardDuplicate(irConverters, nodeType, "IR converter");
		irConverters.put(nodeType, converter);
	}

	@Override
	public void rewriteRule(IRewriteRule rule) {
		rewriteRules.add(rule);
	}

	@Override
	public void layoutHandler(String namespace, String name, ILayoutDirectiveHandler handler) {
		String key = (namespace + ":" + name).toLowerCase(Locale.ROOT);
		guardDuplicate(layoutHandlers, key, "layout handler");
		layoutHandlers.put(key, handler);
	}

	@Override
	public void linkingRule(ILinkingRule rule) {
		linkingRules.add(rule);
	}

	@Override
	public void linkingDirectiveHandler(String namespace, String name, ILinkingDirectiveHandler handler) {
		String key = (namespace + ":" + name).toLowerCase(Locale.ROOT);
		guardDuplicate(linkingDirectiveHandlers, key, "linking directive handler");
		linkingDirectiveHandlers.put(key, handler);
	}

	@Override
	public void emissionContributor(IEmissionContributor contributor) {
		emissionContributors.add(contributor);
	}

	// --- Getter methods (read side, used by Compiler) ---

	/**
	 * Returns the character sequences the Phase 1 lexer emits as {@link
	 * org.evochora.compiler.model.token.TokenType#SYMBOL} tokens. Every symbol was checked
	 * against the symbol alphabet when it was registered; a symbol registered by several features
	 * appears once.
	 *
	 * @return An unmodifiable view of the live set.
	 */
	public Set<String> lexerSymbols() {
		return Collections.unmodifiableSet(lexerSymbols);
	}

	/**
	 * Returns the scan handlers of the Lexer phase by the directive word they were registered
	 * under, upper-cased. The phase hands a line to the handler of the first registered word it
	 * holds. A second registration for the same word is rejected when it happens.
	 *
	 * @return An unmodifiable view of the live map, so handlers registered afterwards also become
	 *         visible through a view handed out earlier.
	 */
	public Map<String, IDependencyScanHandler> dependencyScanHandlers() {
		return Collections.unmodifiableMap(dependencyScanHandlers);
	}

	/**
	 * Returns the Phase 4 handlers that set up module relationships, keyed by the
	 * {@link IDependencyInfo} subclass each one processes. Every handler's own type parameter equals
	 * its key, but the map type cannot express that link, so a caller invoking a handler has to
	 * restore it with an unchecked cast. A second registration for the same dependency type is
	 * rejected when it happens.
	 *
	 * @return An unmodifiable view of the live map.
	 */
	public Map<Class<? extends IDependencyInfo>, IDependencySetupHandler<?>> dependencySetupHandlers() {
		return Collections.unmodifiableMap(dependencySetupHandlers);
	}

	/**
	 * Returns the statically registered Phase 2 preprocessor handlers, keyed by the upper-cased token
	 * text that triggers them. Handlers that a running preprocessor adds for itself — macro expansion,
	 * for instance — are held by
	 * {@link org.evochora.compiler.frontend.preprocessor.PreProcessorContext#handlers()}
	 * and never appear here. A second registration under the same name, compared case-insensitively,
	 * is rejected when it happens.
	 *
	 * @return An unmodifiable view of the live map.
	 */
	public Map<String, IPreProcessorHandler> preprocessorHandlers() {
		return Collections.unmodifiableMap(preprocessorHandlers);
	}

	/**
	 * A kind of block of Phase 2 as a feature registered it: the kind, the handler of its openers
	 * and whether its body is stored for later.
	 *
	 * @param kind    The openers, closer and dividers.
	 * @param handler The handler called for a whole block of the kind.
	 * @param stored  Whether the body is stored for later rather than processed in place.
	 */
	public record PreprocessorBlock(BlockKind kind, IPreProcessorBlockHandler handler, boolean stored) {
	}

	/**
	 * Returns the Phase 2 block kinds with their handlers in registration order. Whether two kinds
	 * claim the same word is checked when they are registered into the preprocessor's registry.
	 *
	 * @return An unmodifiable view of the live list.
	 */
	public List<PreprocessorBlock> preprocessorBlocks() {
		return Collections.unmodifiableList(preprocessorBlocks);
	}

	/**
	 * Returns the Phase 2 directives that may stand only at the top level, upper-cased.
	 *
	 * @return An unmodifiable view of the live set.
	 */
	public Set<String> preprocessorTopLevelOnly() {
		return Collections.unmodifiableSet(preprocessorTopLevelOnly);
	}

	/**
	 * Returns the Phase 3 statement handlers, keyed by the upper-cased keyword that selects them.
	 * A keyword without an entry here is parsed by {@link #defaultParserStatementHandler()}.
	 * A second registration for the same keyword, compared case-insensitively, is rejected when it
	 * happens.
	 *
	 * @return An unmodifiable view of the live map.
	 */
	public Map<String, IParserStatementHandler> parserStatementHandlers() {
		return Collections.unmodifiableMap(parserStatementHandlers);
	}

	/**
	 * A kind of block of Phase 3 as a feature registered it: the kind and the handler of its
	 * openers.
	 *
	 * @param kind    The openers, closer and dividers.
	 * @param handler The handler called for a whole block of the kind.
	 */
	public record ParserBlock(BlockKind kind, IParserBlockHandler handler) {
	}

	/**
	 * Returns the Phase 3 block kinds with their handlers in registration order. Whether two kinds
	 * claim the same word is checked when they are registered into the parser's registry.
	 *
	 * @return An unmodifiable view of the live list.
	 */
	public List<ParserBlock> parserBlocks() {
		return Collections.unmodifiableList(parserBlocks);
	}

	/**
	 * Returns the Phase 3 fallback handler, which parses every statement whose keyword no entry of
	 * {@link #parserStatementHandlers()} claims. At most one may exist; a second registration is
	 * rejected when it happens.
	 *
	 * @return The registered fallback handler, or {@code null} if no feature registered one, in which
	 *         case the parser is left without a fallback.
	 */
	public IParserStatementHandler defaultParserStatementHandler() {
		return defaultParserStatementHandler;
	}

	/**
	 * Returns the collectors of the first Phase 4 pass, which extract symbols from AST nodes, keyed by
	 * node class. Lookup is by the node's exact class: no walk up the class hierarchy and no fallback,
	 * so a node class without its own entry contributes no symbols. A second registration for the same
	 * node class is rejected when it happens.
	 *
	 * @return An unmodifiable view of the live map.
	 */
	public Map<Class<? extends AstNode>, ISymbolCollector> symbolCollectors() {
		return Collections.unmodifiableMap(symbolCollectors);
	}

	/**
	 * Returns the handlers of the second Phase 4 pass, which validate AST nodes against the symbol
	 * table filled by the first pass, keyed by node class. Lookup is by the node's exact class: no
	 * walk up the class hierarchy and no fallback, so a node class without its own entry is not
	 * validated. A second registration for the same node class is rejected when it happens.
	 *
	 * @return An unmodifiable view of the live map.
	 */
	public Map<Class<? extends AstNode>, IAnalysisHandler> analysisHandlers() {
		return Collections.unmodifiableMap(analysisHandlers);
	}

	/**
	 * Returns the Phase 5 contributors that add debugger token map entries, keyed by AST node class.
	 * Lookup is by the node's exact class with no fallback, so a node class without its own entry
	 * produces no token map entries. A second registration for the same node class is rejected when
	 * it happens.
	 *
	 * @return An unmodifiable view of the live map.
	 */
	public Map<Class<? extends AstNode>, ITokenMapContributor> tokenMapContributors() {
		return Collections.unmodifiableMap(tokenMapContributors);
	}

	/**
	 * Returns the Phase 6 handlers that collect node replacements and constants, keyed by AST node
	 * class. Lookup is by the node's exact class with no fallback, so a node class without its own
	 * entry passes through post-processing unchanged. A second registration for the same node class
	 * is rejected when it happens.
	 *
	 * @return An unmodifiable view of the live map.
	 */
	public Map<Class<? extends AstNode>, IPostProcessHandler> postProcessHandlers() {
		return Collections.unmodifiableMap(postProcessHandlers);
	}

	/**
	 * Returns the Phase 7 converters from AST to IR, keyed by AST node class. Unlike the earlier
	 * per-node registries, resolution walks the node's class hierarchy and the AST interfaces it
	 * implements, and ends at the IR generator's default converter, so an entry registered for a base
	 * class also serves its subclasses. Every converter's own type parameter equals its key, which the
	 * map type cannot express. A second registration for the same node class is rejected when it happens.
	 *
	 * @return An unmodifiable view of the live map.
	 */
	public Map<Class<? extends AstNode>, IAstNodeToIrConverter<?>> irConverters() {
		return Collections.unmodifiableMap(irConverters);
	}

	/**
	 * Returns the Phase 8 rules that rewrite the IR item list, in registration order. Each rule is
	 * applied to the whole item list and its result is the input of the next one, so registration
	 * order determines what a later rule sees. Repeated registration is not rejected here.
	 *
	 * @return An unmodifiable view of the live list.
	 */
	public List<IRewriteRule> rewriteRules() {
		return Collections.unmodifiableList(rewriteRules);
	}

	/**
	 * Returns the Phase 9 handlers that process IR directives while addresses are assigned, keyed by
	 * the lower-cased {@code "namespace:name"} of the directive. A directive without a handler is
	 * skipped by the layout engine rather than reported, because not every directive needs
	 * layout-phase work. A second registration for the same namespace and name, compared
	 * case-insensitively, is rejected when it happens.
	 *
	 * @return An unmodifiable view of the live map.
	 */
	public Map<String, ILayoutDirectiveHandler> layoutHandlers() {
		return Collections.unmodifiableMap(layoutHandlers);
	}

	/**
	 * Returns the Phase 10 rules that transform instructions during linking, in registration order.
	 * Each instruction passes through all rules in this order and every rule receives the instruction
	 * the previous one returned, so registration order determines what a later rule sees. Repeated
	 * registration is not rejected here.
	 *
	 * @return An unmodifiable view of the live list.
	 */
	public List<ILinkingRule> linkingRules() {
		return Collections.unmodifiableList(linkingRules);
	}

	/**
	 * Returns the Phase 10 handlers that process IR directives while cross-references are resolved,
	 * keyed by the lower-cased {@code "namespace:name"} of the directive. A directive without a
	 * handler is skipped by the linker rather than reported, because not every directive needs
	 * linking-phase work. A second registration for the same namespace and name, compared
	 * case-insensitively, is rejected when it happens.
	 *
	 * @return An unmodifiable view of the live map.
	 */
	public Map<String, ILinkingDirectiveHandler> linkingDirectiveHandlers() {
		return Collections.unmodifiableMap(linkingDirectiveHandlers);
	}

	/**
	 * Returns the Phase 11 contributors that collect feature metadata for the program artifact, in
	 * registration order. Every contributor is offered every IR item, so they observe rather than
	 * claim items and no contributor can keep another from seeing an item. Repeated registration is
	 * not rejected here.
	 *
	 * @return An unmodifiable view of the live list.
	 */
	public List<IEmissionContributor> emissionContributors() {
		return Collections.unmodifiableList(emissionContributors);
	}

	private static <K> void guardDuplicate(Map<K, ?> map, K key, String description) {
		if (map.containsKey(key)) {
			throw new IllegalStateException(description + " already registered for: " + key);
		}
	}
}
