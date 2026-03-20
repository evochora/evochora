package org.evochora.compiler;

import org.evochora.compiler.backend.emit.IEmissionContributor;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.link.ILinkingDirectiveHandler;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.module.IDependencyInfo;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;
import org.evochora.compiler.frontend.parser.IParserStatementHandler;
import org.evochora.compiler.frontend.postprocess.IPostProcessHandler;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorHandler;
import org.evochora.compiler.frontend.semantics.IDependencySetupHandler;
import org.evochora.compiler.frontend.semantics.analysis.IAnalysisHandler;
import org.evochora.compiler.frontend.semantics.analysis.ISymbolCollector;
import org.evochora.compiler.frontend.tokenmap.ITokenMapContributor;
import org.evochora.compiler.model.ast.AstNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	// List-based registrations preserve registration order. Within a feature, the feature
	// controls handler ordering. No guardDuplicate — ordered sequential execution is intended.
	private final List<IDependencyScanHandler> dependencyScanHandlers = new ArrayList<>();
	private final List<IEmissionRule> emissionRules = new ArrayList<>();
	private final List<ILinkingRule> linkingRules = new ArrayList<>();
	private final List<IEmissionContributor> emissionContributors = new ArrayList<>();

	// --- IFeatureRegistrationContext implementation (write side) ---

	@Override
	public void dependencyScanHandler(IDependencyScanHandler handler) {
		dependencyScanHandlers.add(handler);
	}

	@Override
	public <T extends IDependencyInfo> void dependencySetupHandler(
			Class<T> type, IDependencySetupHandler<T> handler) {
		guardDuplicate(dependencySetupHandlers, type, "dependency setup handler");
		dependencySetupHandlers.put(type, handler);
	}

	@Override
	public void preprocessor(String name, IPreProcessorHandler handler) {
		String key = name.toUpperCase();
		guardDuplicate(preprocessorHandlers, key, "preprocessor handler");
		preprocessorHandlers.put(key, handler);
	}

	@Override
	public void parserStatement(String keyword, IParserStatementHandler handler) {
		String key = keyword.toUpperCase();
		guardDuplicate(parserStatementHandlers, key, "parser statement handler");
		parserStatementHandlers.put(key, handler);
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
	public void emissionRule(IEmissionRule rule) {
		emissionRules.add(rule);
	}

	@Override
	public void layoutHandler(String namespace, String name, ILayoutDirectiveHandler handler) {
		String key = (namespace + ":" + name).toLowerCase();
		guardDuplicate(layoutHandlers, key, "layout handler");
		layoutHandlers.put(key, handler);
	}

	@Override
	public void linkingRule(ILinkingRule rule) {
		linkingRules.add(rule);
	}

	@Override
	public void linkingDirectiveHandler(String namespace, String name, ILinkingDirectiveHandler handler) {
		String key = (namespace + ":" + name).toLowerCase();
		guardDuplicate(linkingDirectiveHandlers, key, "linking directive handler");
		linkingDirectiveHandlers.put(key, handler);
	}

	@Override
	public void emissionContributor(IEmissionContributor contributor) {
		emissionContributors.add(contributor);
	}

	// --- Getter methods (read side, used by Compiler) ---

	public List<IDependencyScanHandler> dependencyScanHandlers() {
		return Collections.unmodifiableList(dependencyScanHandlers);
	}

	public Map<Class<? extends IDependencyInfo>, IDependencySetupHandler<?>> dependencySetupHandlers() {
		return Collections.unmodifiableMap(dependencySetupHandlers);
	}

	public Map<String, IPreProcessorHandler> preprocessorHandlers() {
		return Collections.unmodifiableMap(preprocessorHandlers);
	}

	public Map<String, IParserStatementHandler> parserStatementHandlers() {
		return Collections.unmodifiableMap(parserStatementHandlers);
	}

	public IParserStatementHandler defaultParserStatementHandler() {
		return defaultParserStatementHandler;
	}

	public Map<Class<? extends AstNode>, ISymbolCollector> symbolCollectors() {
		return Collections.unmodifiableMap(symbolCollectors);
	}

	public Map<Class<? extends AstNode>, IAnalysisHandler> analysisHandlers() {
		return Collections.unmodifiableMap(analysisHandlers);
	}

	public Map<Class<? extends AstNode>, ITokenMapContributor> tokenMapContributors() {
		return Collections.unmodifiableMap(tokenMapContributors);
	}

	public Map<Class<? extends AstNode>, IPostProcessHandler> postProcessHandlers() {
		return Collections.unmodifiableMap(postProcessHandlers);
	}

	public Map<Class<? extends AstNode>, IAstNodeToIrConverter<?>> irConverters() {
		return Collections.unmodifiableMap(irConverters);
	}

	public List<IEmissionRule> emissionRules() {
		return Collections.unmodifiableList(emissionRules);
	}

	public Map<String, ILayoutDirectiveHandler> layoutHandlers() {
		return Collections.unmodifiableMap(layoutHandlers);
	}

	public List<ILinkingRule> linkingRules() {
		return Collections.unmodifiableList(linkingRules);
	}

	public Map<String, ILinkingDirectiveHandler> linkingDirectiveHandlers() {
		return Collections.unmodifiableMap(linkingDirectiveHandlers);
	}

	public List<IEmissionContributor> emissionContributors() {
		return Collections.unmodifiableList(emissionContributors);
	}

	private static <K> void guardDuplicate(Map<K, ?> map, K key, String description) {
		if (map.containsKey(key)) {
			throw new IllegalStateException(description + " already registered for: " + key);
		}
	}
}
