package org.evochora.compiler;

import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.module.IDependencyScanHandler;
import org.evochora.compiler.frontend.parser.IParserDirectiveHandler;
import org.evochora.compiler.frontend.postprocess.IPostProcessHandler;
import org.evochora.compiler.frontend.preprocessor.IPreProcessorDirectiveHandler;
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

	private final List<IDependencyScanHandler> dependencyScanHandlers = new ArrayList<>();
	private final Map<String, IPreProcessorDirectiveHandler> preprocessorHandlers = new HashMap<>();
	private final Map<String, IParserDirectiveHandler> parserHandlers = new HashMap<>();
	private final Map<Class<? extends AstNode>, ISymbolCollector> symbolCollectors = new HashMap<>();
	private final Map<Class<? extends AstNode>, IAnalysisHandler> analysisHandlers = new HashMap<>();
	private final Map<Class<? extends AstNode>, ITokenMapContributor> tokenMapContributors = new HashMap<>();
	private final Map<Class<? extends AstNode>, IPostProcessHandler> postProcessHandlers = new HashMap<>();
	private final Map<Class<? extends AstNode>, IAstNodeToIrConverter<?>> irConverters = new HashMap<>();
	private final List<IEmissionRule> emissionRules = new ArrayList<>();
	private final Map<String, ILayoutDirectiveHandler> layoutHandlers = new HashMap<>();
	private final List<ILinkingRule> linkingRules = new ArrayList<>();

	// --- IFeatureRegistrationContext implementation (write side) ---

	@Override
	public void dependencyScanHandler(IDependencyScanHandler handler) {
		dependencyScanHandlers.add(handler);
	}

	@Override
	public void preprocessor(String directive, IPreProcessorDirectiveHandler handler) {
		preprocessorHandlers.put(directive.toUpperCase(), handler);
	}

	@Override
	public void parser(String directive, IParserDirectiveHandler handler) {
		parserHandlers.put(directive.toUpperCase(), handler);
	}

	@Override
	public void symbolCollector(Class<? extends AstNode> nodeType, ISymbolCollector collector) {
		symbolCollectors.put(nodeType, collector);
	}

	@Override
	public void analysisHandler(Class<? extends AstNode> nodeType, IAnalysisHandler handler) {
		analysisHandlers.put(nodeType, handler);
	}

	@Override
	public void tokenMapContributor(Class<? extends AstNode> nodeType, ITokenMapContributor contributor) {
		tokenMapContributors.put(nodeType, contributor);
	}

	@Override
	public void postProcessHandler(Class<? extends AstNode> nodeType, IPostProcessHandler handler) {
		postProcessHandlers.put(nodeType, handler);
	}

	@Override
	public <T extends AstNode> void irConverter(Class<T> nodeType, IAstNodeToIrConverter<T> converter) {
		irConverters.put(nodeType, converter);
	}

	@Override
	public void emissionRule(IEmissionRule rule) {
		emissionRules.add(rule);
	}

	@Override
	public void layoutHandler(String namespace, String name, ILayoutDirectiveHandler handler) {
		layoutHandlers.put((namespace + ":" + name).toLowerCase(), handler);
	}

	@Override
	public void linkingRule(ILinkingRule rule) {
		linkingRules.add(rule);
	}

	// --- Getter methods (read side, used by Compiler) ---

	public List<IDependencyScanHandler> dependencyScanHandlers() {
		return Collections.unmodifiableList(dependencyScanHandlers);
	}

	public Map<String, IPreProcessorDirectiveHandler> preprocessorHandlers() {
		return Collections.unmodifiableMap(preprocessorHandlers);
	}

	public Map<String, IParserDirectiveHandler> parserHandlers() {
		return Collections.unmodifiableMap(parserHandlers);
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
}
