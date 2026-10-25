package org.evochora.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.api.FlagSetting;
import org.evochora.compiler.backend.emit.IEmissionContributor;
import org.evochora.compiler.backend.rewrite.IRewriteRule;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.link.ILinkingDirectiveHandler;
import org.evochora.compiler.model.symbols.Symbol;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.expression.EvaluationResult;
import org.evochora.compiler.expression.IOperation;
import org.evochora.compiler.expression.IOperationGroup;
import org.evochora.compiler.expression.NameAnswer;
import org.evochora.compiler.expression.OperationResult;
import org.evochora.compiler.expression.parser.ParseResult;
import org.evochora.compiler.frontend.parser.OperandReading;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrOperand;
import org.evochora.compiler.model.ir.IrValue;
import org.evochora.compiler.model.expression.Expression;
import org.evochora.compiler.model.expression.Value;
import org.evochora.compiler.model.symbols.Resolution;

/**
 * Enforces the structure of the compiler described in the <em>Compiler</em> section of
 * {@code AGENTS.md}: a feature-agnostic core, features that know each other only where one
 * declares that it requires another and that hold no state, three data formats that do not reach into one another, and phases that meet only in
 * {@code Compiler}.
 * <p>
 * Each rule here is the enforceable form of one sentence of that section. A rule fires naming
 * the classes involved, and the ways forward are the same as for the package graph: withdraw the
 * dependency, or edit this class and let the edit be reviewed. The rules are kept to what the
 * section states, so that a new one is a decision about the architecture, not a reflex.
 */
@AnalyzeClasses(packages = "org.evochora", importOptions = {ImportOption.DoNotIncludeTests.class, ExcludeTestFixturesAndJmh.class})
@ArchTag("integration")
class CompilerArchitectureRulesTest {

    /** Package holding the whole compiler. */
    private static final String COMPILER = "org.evochora.compiler";

    /** Package holding one sub-package per feature. */
    private static final String FEATURES = COMPILER + ".features";

    /** Package holding the behaviour of computations, shared by the phases that compute. */
    private static final String EXPRESSION = COMPILER + ".expression";

    /** The classes that run the phases of the pipeline, in pipeline order. */
    private static final String[] PHASE_CLASSES = {
            COMPILER + ".frontend.lexer.Lexer",
            COMPILER + ".frontend.preprocessor.PreProcessor",
            COMPILER + ".frontend.parser.Parser",
            COMPILER + ".frontend.semantics.SemanticAnalyzer",
            COMPILER + ".frontend.tokenmap.TokenMapGenerator",
            COMPILER + ".frontend.postprocess.AstPostProcessor",
            COMPILER + ".frontend.irgen.IrGenerator",
            COMPILER + ".backend.rewrite.IrRewriter",
            COMPILER + ".backend.layout.LayoutEngine",
            COMPILER + ".backend.link.Linker",
            COMPILER + ".backend.emit.Emitter",
    };

    /** The packages holding the phases of the pipeline, in pipeline order. */
    private static final String[] PHASE_PACKAGES = {
            COMPILER + ".frontend.lexer..",
            COMPILER + ".frontend.preprocessor..",
            COMPILER + ".frontend.parser..",
            COMPILER + ".frontend.semantics..",
            COMPILER + ".frontend.tokenmap..",
            COMPILER + ".frontend.postprocess..",
            COMPILER + ".frontend.irgen..",
            COMPILER + ".backend.rewrite..",
            COMPILER + ".backend.layout..",
            COMPILER + ".backend.link..",
            COMPILER + ".backend.emit..",
    };

    /**
     * The core never references a feature: the only place that knows which features exist is
     * {@code StandardFeatures}.
     * <p>
     * Features depend on phases, not the other way round. A phase, a context, the symbol table
     * or a data format that named a feature class would have to change whenever that feature
     * does, and a new feature could not be added without touching the core.
     */
    @ArchTest
    static void onlyStandardFeaturesReferencesFeatures(JavaClasses classes) {
        noClasses().that().resideInAPackage(COMPILER + "..")
                .and().resideOutsideOfPackage(FEATURES + "..")
                .and().doNotBelongToAnyOf(StandardFeatures.class)
                .should().dependOnClassesThat().resideInAPackage(FEATURES + "..")
                .check(classes);
    }

    /**
     * A feature references another feature only where it declares that it requires it: from its
     * feature class, in {@link ICompilerFeature#requires()}, to the feature class it requires.
     * <p>
     * A feature is the unit of change. A handler that needs another feature's work reads it from
     * the phase's input data, never from the other feature's classes; otherwise a change to one
     * feature spreads into the next. A feature that builds on another names it in one place, and
     * the rule reads that declaration rather than a list of its own, so what is allowed is
     * exactly what the features declare.
     */
    @ArchTest
    static void featuresReferenceOnlyTheFeaturesTheyRequire(JavaClasses classes) {
        Map<Class<?>, Set<Class<? extends ICompilerFeature>>> declared = new HashMap<>();
        StandardFeatures.all().forEach(feature -> declared.put(feature.getClass(), feature.requires()));

        classes().that().resideInAPackage(FEATURES + "..")
                .should(referenceOtherFeaturesOnlyAsDeclared(declared))
                .check(classes);
    }

    private static ArchCondition<JavaClass> referenceOtherFeaturesOnlyAsDeclared(
            Map<Class<?>, Set<Class<? extends ICompilerFeature>>> declared) {
        return new ArchCondition<>("reference another feature only from its feature class to a feature class it requires") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String own = featureOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String other = featureOf(target);
                    if (other == null || other.equals(own)) {
                        continue;
                    }
                    boolean allowed = declared.containsKey(origin.reflect())
                            && declared.get(origin.reflect()).contains(target.reflect());
                    if (!allowed) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * Names the feature a class belongs to: the package directly below the features package, or
     * none for a class outside it.
     */
    private static String featureOf(JavaClass clazz) {
        String prefix = FEATURES + ".";
        String packageName = clazz.getPackageName();
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String rest = packageName.substring(prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /**
     * Features have no state: a feature class only registers its handlers and holds nothing.
     * <p>
     * Compilation data flows through the phase contexts. A field on a feature would be state that
     * survives a compilation, or a constructor parameter that has to be supplied by whoever
     * instantiates the feature.
     */
    @ArchTest
    static void featuresHaveNoFields(JavaClasses classes) {
        classes().that().implement(ICompilerFeature.class)
                .should(haveNoFields())
                .check(classes);
    }

    /**
     * Static fields of a feature hold constants, not state.
     * <p>
     * A static field that changes carries state from one compilation into the next, and the
     * artifact of a source then depends on what was compiled before it in the same process. The
     * field has to be final, and it may not hold an atomic, the usual shape of a counter. A
     * collection is not examined: its type does not tell whether it can change.
     */
    @ArchTest
    static void featureStaticFieldsAreConstants(JavaClasses classes) {
        fields().that().areDeclaredInClassesThat().resideInAPackage(FEATURES + "..")
                .and().areStatic()
                .should().beFinal()
                .andShould().notHaveRawType(resideInAPackage("java.util.concurrent.atomic.."))
                .check(classes);
    }

    /**
     * A phase package is flat: its orchestrator, registries, contexts and handler interfaces
     * lie side by side, and it has no sub-packages.
     * <p>
     * A handler interface lives in the package of the phase it serves, and there is no separate
     * package for interfaces. A sub-package of a phase is such a package under another name, and
     * it splits the phase's surface across two places for no reason the phase has.
     */
    @ArchTest
    static void phasePackagesAreFlat(JavaClasses classes) {
        for (String phasePackage : PHASE_PACKAGES) {
            String flat = phasePackage.substring(0, phasePackage.length() - 2);
            classes().that().resideInAPackage(phasePackage)
                    .should().resideInAPackage(flat)
                    .check(classes);
        }
    }

    /**
     * A top-level interface carries the {@code I} prefix, except the types that name a kind of data.
     * <p>
     * The prefix marks a role: what a feature implements (a handler, a rule, an operation), what
     * it is handed or calls (a context, a registration), and what a node can do (a capability
     * such as {@code ISourceLocatable}). A type that says what a value is carries no prefix: the
     * core builds such values and their users tell them apart with a {@code switch}. No property
     * of the code tells a role from a kind of data, so the kinds of data are listed here, and each
     * entry is a decision: the types every AST node, AST operand, IR item, IR operand and IR value
     * implements, the value family the formats share, the outcome of a name lookup, the expression
     * tree, the outcomes of reading an operand and of parsing, answering and computing an
     * expression, and the configuration's decision about a flag. A nested interface is named by its owner, as in
     * {@code IInstructionSet.Signature}, and is not covered.
     */
    @ArchTest
    static void topLevelInterfacesCarryThePrefix(JavaClasses classes) {
        classes().that().resideInAPackage(COMPILER + "..").and().areInterfaces().and().areTopLevelClasses()
                .and().doNotBelongToAnyOf(AstNode.class, OperandNode.class, IrItem.class, IrOperand.class, IrValue.class, Resolution.class, Value.class,
                        Expression.class, OperationResult.class, EvaluationResult.class, NameAnswer.class, ParseResult.class,
                        OperandReading.class, FlagSetting.class)
                .should().haveNameMatching(".*\\.I[A-Z][A-Za-z0-9]*")
                .check(classes);
    }

    /**
     * The backend sees the IR and the instruction set, nothing of the frontend.
     * <p>
     * From phase 7 on the IR is the single source of truth: what is not in the IR does not
     * exist for the backend. Neither a backend package nor a handler a feature registers for a
     * backend phase may read the symbol table, the AST or the tokens. A handler that needs
     * something from there says that the IR is missing it, and then it belongs into the IR.
     * <p>
     * The operations of computations are held to the same rule, as the layout computes with
     * them, and so are the parts of the expression package only the frontend may use: the
     * parser, which reads tokens, and the bridge to the symbol table.
     */
    @ArchTest
    static void backendReadsOnlyTheIrAndTheInstructionSet(JavaClasses classes) {
        noClasses().that().resideInAPackage(COMPILER + ".backend..")
                .or().implement(ILayoutDirectiveHandler.class)
                .or().implement(ILinkingRule.class)
                .or().implement(ILinkingDirectiveHandler.class)
                .or().implement(IRewriteRule.class)
                .or().implement(IEmissionContributor.class)
                .or().implement(IOperation.class)
                .or().implement(IOperationGroup.class)
                .should().dependOnClassesThat().resideInAnyPackage(
                        COMPILER + ".frontend..",
                        COMPILER + ".model.ast..",
                        COMPILER + ".model.token..",
                        COMPILER + ".model.symbols..",
                        EXPRESSION + ".parser..",
                        EXPRESSION + ".symbols..")
                .check(classes);
    }

    /**
     * The compiler sees its target through two doors: {@code EnvironmentProperties}, the world
     * it lays a program out for, and {@code IInstructionSet}, the compiler's view of the
     * instruction set and the cell encoding. Only the adapter behind that view reads the
     * runtime's declarations.
     * <p>
     * Opcodes, signatures, register banks and molecule types are what the compiler targets.
     * Reading them from the runtime in one place and through the view in another gives the
     * compiler two pictures of the same target, and a test can replace neither.
     */
    @ArchTest
    static void onlyTheAdapterReadsTheRuntime(JavaClasses classes) {
        noClasses().that().resideInAPackage(COMPILER + "..")
                .and().resideOutsideOfPackage(COMPILER + ".isa..")
                .should().dependOnClassesThat(resideInAPackage("org.evochora.runtime..")
                        .and(not(type(EnvironmentProperties.class))))
                .check(classes);
    }

    /**
     * No phase decides by the kind of a symbol; only the token map names the kinds, when it
     * reports them to the debugger.
     * <p>
     * The kinds in {@code Symbol.Type} are those of the features: a label, a procedure, a
     * register alias. A phase that branches on them knows those features. What a phase may do
     * with a symbol, it learns from the capabilities of the node that defined it.
     */
    @ArchTest
    static void phasesDoNotBranchOnSymbolKinds(JavaClasses classes) {
        noClasses().that().resideInAnyPackage(COMPILER + ".frontend..", COMPILER + ".backend..")
                .and().resideOutsideOfPackage(COMPILER + ".frontend.tokenmap..")
                .should().dependOnClassesThat().haveFullyQualifiedName(COMPILER + ".model.symbols.Symbol$Type")
                .check(classes);
    }

    /**
     * Nobody reads the kind of a symbol except the token map, which reports the kinds to the
     * visualizer. A feature that needs to know what an identifier stands for asks the node
     * that defined it: an {@code IIdentifierBinding} says what the identifier binds to, an
     * {@code IJumpTarget} that it marks a place in the code. Those capabilities are the
     * model's, the kinds are the features'.
     */
    @ArchTest
    static void nobodyReadsTheKindOfASymbol(JavaClasses classes) {
        noClasses().that().resideInAPackage(COMPILER + "..")
                .and().resideOutsideOfPackage(COMPILER + ".frontend.tokenmap..")
                .and().doNotHaveFullyQualifiedName(Symbol.class.getName())
                .should().callMethod(Symbol.class, "type")
                .check(classes);
    }

    /**
     * AST nodes and IR items are records: pure data, immutable, equal by content.
     * <p>
     * A record says at a glance what a class has to be read for. The one exception is
     * {@code IrInstruction}: a feature may subtype it to carry operands beyond the main ones,
     * which a record cannot do, and {@code IrCallInstruction} does.
     */
    @ArchTest
    static void dataTypesAreRecords(JavaClasses classes) {
        classes().that().implement(AstNode.class).or().implement(IrItem.class)
                .and().areNotInterfaces()
                .and().areNotAssignableTo(IrInstruction.class)
                .should().beRecords()
                .check(classes);
    }

    /**
     * The model package holds nothing but its sub-packages: the three data formats and the
     * symbol table.
     * <p>
     * A class placed directly in {@code model} is neither a data format nor a symbol, and what
     * is not data has no place among the data formats. The helpers that walk the AST for a
     * phase live with that phase.
     */
    @ArchTest
    static void modelPackageHoldsOnlyItsSubPackages(JavaClasses classes) {
        noClasses().should().resideInAPackage(COMPILER + ".model")
                .check(classes);
    }

    /**
     * The three data formats are separate: token, AST and IR types do not reference one another.
     * <p>
     * An AST node carries a token's {@code SourceInfo}, never the token, and an IR item carries
     * nothing of the AST it was made from. The rule is checked on the types themselves, wherever
     * they live, because features define AST nodes and IR items of their own. The values the
     * formats share ({@code model.expression}) reference none of them either, so that no format
     * reaches another through a value.
     */
    @ArchTest
    static void dataFormatsDoNotReferenceEachOther(JavaClasses classes) {
        noClasses().that().resideInAPackage(COMPILER + ".model.token..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        COMPILER + ".model.ast..", COMPILER + ".model.ir..")
                .check(classes);
        noClasses().that().implement(AstNode.class).or().resideInAPackage(COMPILER + ".model.ast..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        COMPILER + ".model.token..", COMPILER + ".model.ir..")
                .check(classes);
        noClasses().that().implement(IrItem.class).or().resideInAPackage(COMPILER + ".model.ir..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        COMPILER + ".model.token..", COMPILER + ".model.ast..")
                .check(classes);
        noClasses().that().resideInAPackage(COMPILER + ".model.expression..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        COMPILER + ".model.token..", COMPILER + ".model.ast..",
                        COMPILER + ".model.ir..", COMPILER + ".model.symbols..")
                .check(classes);
    }

    /**
     * The root of the expression package holds what frontend and backend share, and so reads
     * neither its own sub-packages nor the data only the frontend has: the tokens, the AST and
     * the symbol table.
     * <p>
     * The layout computes with the evaluator, the operations and the registry as phase 4 does.
     * The parser, which reads tokens, and the bridge to the symbol table are the frontend's
     * part and lie in sub-packages, which the backend may not reference; a root class that
     * reached them would carry them into the backend.
     */
    @ArchTest
    static void expressionRootReadsNothingOfTheFrontend(JavaClasses classes) {
        noClasses().that().resideInAPackage(EXPRESSION)
                .should().dependOnClassesThat().resideInAnyPackage(
                        EXPRESSION + ".parser..",
                        EXPRESSION + ".symbols..",
                        COMPILER + ".model.token..",
                        COMPILER + ".model.ast..",
                        COMPILER + ".model.symbols..")
                .check(classes);
    }

    /**
     * The data of computations knows nothing of the behaviour that computes them: neither the
     * expression tree and the values ({@code model.expression}) nor the symbol table, which
     * records them, references the expression package.
     * <p>
     * The tree is data every phase may hold; if it reached the evaluator or the registry, a
     * format would depend on the operations a compilation happens to register.
     */
    @ArchTest
    static void computationDataReferencesNoBehaviour(JavaClasses classes) {
        noClasses().that().resideInAnyPackage(COMPILER + ".model.expression..", COMPILER + ".model.symbols..")
                .should().dependOnClassesThat().resideInAPackage(EXPRESSION + "..")
                .check(classes);
    }

    /**
     * Phases never call other phases: {@code Compiler} is the only class that knows the order.
     * <p>
     * A phase receives its input from the compiler and returns its result to it. A phase that
     * reached into another phase would fix the order of the pipeline in a second place.
     */
    @ArchTest
    static void phasesDoNotReferenceEachOther(JavaClasses classes) {
        for (String phase : PHASE_CLASSES) {
            noClasses().that().haveFullyQualifiedName(phase)
                    .should().dependOnClassesThat().haveNameMatching(otherPhasesThan(phase))
                    .check(classes);
        }
    }

    /**
     * A phase package references only the packages of earlier phases.
     * <p>
     * A phase receives the results of earlier phases as its input, so its package may use their
     * types. A type of a later phase has no business in an earlier one: it would tie the earlier
     * phase to a decision the pipeline has not reached yet. Packages that hold no phase, such as
     * the data formats, the diagnostics or the instruction set, are outside the layering and
     * may be used by every phase.
     */
    @ArchTest
    static void phasePackagesReferenceOnlyEarlierPhases(JavaClasses classes) {
        var layers = layeredArchitecture().consideringOnlyDependenciesInLayers();
        for (String phasePackage : PHASE_PACKAGES) {
            layers = layers.layer(phasePackage).definedBy(phasePackage);
        }
        for (int i = 0; i < PHASE_PACKAGES.length; i++) {
            String[] earlier = java.util.Arrays.copyOfRange(PHASE_PACKAGES, 0, i);
            layers = i == 0
                    ? layers.whereLayer(PHASE_PACKAGES[i]).mayNotAccessAnyLayer()
                    : layers.whereLayer(PHASE_PACKAGES[i]).mayOnlyAccessLayers(earlier);
        }
        layers.check(classes);
    }

    /**
     * A phase uses only its own logic: from another phase it takes results, which are data.
     * <p>
     * A phase receives the results of earlier phases as its input. A class with behaviour that
     * two phases used would tie the later phase to the decisions of the earlier one, and a change
     * there would break at a place nobody connects with it. Logic that phases share lives outside
     * the phases, in the {@code frontend} root or in a phase-free package. So: a class of a phase
     * package that another phase references is a record or an interface; a class of a feature
     * that implements a handler interface of a phase uses classes with behaviour of that phase
     * only, takes from earlier phases records and interfaces only, and references nothing of a
     * later phase, and so does every helper class of the features it reaches, directly or through
     * other helpers, such as a state holder or a condition, which serves the handler's phase
     * through it; a class of a
     * feature that implements no handler interface and that no handler reaches this way uses the
     * logic of at most one phase; and the {@code frontend} root
     * and the phase-free packages reference no phase package at all, so that no class can reach
     * a phase through them. Which phase comes earlier is the order of {@link #PHASE_PACKAGES}.
     * <p>
     * What the rule cannot see: behaviour written into a record or a default method of an
     * interface of a phase package counts as data here, because the form of a class is all a
     * rule can judge. A reviewer reads such methods.
     */
    @ArchTest
    static void phasesShareOnlyData(JavaClasses classes) {
        for (String phasePackage : PHASE_PACKAGES) {
            String[] others = java.util.Arrays.stream(PHASE_PACKAGES)
                    .filter(other -> !other.equals(phasePackage)).toArray(String[]::new);
            noClasses().that().resideInAnyPackage(others)
                    .should().dependOnClassesThat(resideInAPackage(phasePackage).and(not(recordOrInterface())))
                    .check(classes);
        }
        classes().that().resideInAPackage(FEATURES + "..")
                .should(useTheLogicOfTheirPhaseAndTheDataOfEarlierOnes())
                .check(classes);
        noClasses().that().resideInAnyPackage(COMPILER + ".frontend", COMPILER + ".isa..", COMPILER + ".diagnostics..",
                        COMPILER + ".util..", COMPILER + ".api..", COMPILER + ".model..", EXPRESSION + "..")
                .should().dependOnClassesThat().resideInAnyPackage(PHASE_PACKAGES)
                .check(classes);
    }

    private static DescribedPredicate<JavaClass> recordOrInterface() {
        return new DescribedPredicate<>("records or interfaces") {
            @Override
            public boolean test(JavaClass clazz) {
                return clazz.isInterface() || clazz.isRecord();
            }
        };
    }

    /**
     * The index of the phase whose package holds the class, or -1 for a class outside the phases.
     */
    private static int phaseOf(JavaClass clazz) {
        for (int i = 0; i < PHASE_PACKAGES.length; i++) {
            String phasePackage = PHASE_PACKAGES[i].substring(0, PHASE_PACKAGES[i].length() - 2);
            if (clazz.getPackageName().equals(phasePackage)) {
                return i;
            }
        }
        return -1;
    }

    private static ArchCondition<JavaClass> useTheLogicOfTheirPhaseAndTheDataOfEarlierOnes() {
        return new ArchCondition<>("use the logic of their phase only, the data of earlier phases, and nothing of a later one") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                // The phase a feature class serves: the one whose interfaces it implements
                java.util.Set<Integer> served = new java.util.TreeSet<>();
                for (JavaClass implemented : clazz.getAllRawInterfaces()) {
                    int phase = phaseOf(implemented);
                    if (phase >= 0) served.add(phase);
                }
                if (served.size() > 1) {
                    events.add(SimpleConditionEvent.violated(clazz,
                            clazz.getName() + " implements interfaces of more than one phase"));
                    return;
                }
                java.util.Set<String> logicOf = new java.util.TreeSet<>();
                for (Dependency dependency : clazz.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (served.isEmpty()) {
                        int phase = phaseOf(target);
                        if (phase >= 0 && !(target.isInterface() || target.isRecord())) {
                            logicOf.add(PHASE_PACKAGES[phase]);
                        }
                        continue;
                    }
                    int own = served.iterator().next();
                    checkTarget(clazz, target, own, clazz.getName(), events);
                    // A helper of the features that the handler reaches serves the handler's phase
                    // through it, so its dependencies, and those of the helpers it reaches in turn,
                    // are held to the same limits
                    checkHelper(clazz, target, own, clazz.getName(), new java.util.HashSet<>(), events);
                }
                if (logicOf.size() > 1) {
                    events.add(SimpleConditionEvent.violated(clazz,
                            clazz.getName() + " uses the logic of " + logicOf));
                }
            }
        };
    }

    /**
     * Follows a feature helper the handler reaches, and every helper that one reaches, each once,
     * holding what they reference to the limits of the handler's phase.
     */
    private static void checkHelper(JavaClass clazz, JavaClass helper, int own, String via,
                                    java.util.Set<JavaClass> visited, ConditionEvents events) {
        if (!isFeatureHelper(helper) || !visited.add(helper)) return;
        String path = via + " through " + helper.getName();
        for (Dependency inner : helper.getDirectDependenciesFromSelf()) {
            JavaClass target = inner.getTargetClass();
            checkTarget(clazz, target, own, path, events);
            checkHelper(clazz, target, own, path, visited, events);
        }
    }

    /**
     * Whether a class is a helper of the features: a class in a feature package, with behaviour,
     * that implements no interface of a phase and so has no phase of its own.
     */
    private static boolean isFeatureHelper(JavaClass target) {
        if (!target.getPackageName().startsWith(FEATURES) || target.isInterface() || target.isRecord()) {
            return false;
        }
        for (JavaClass implemented : target.getAllRawInterfaces()) {
            if (phaseOf(implemented) >= 0) return false;
        }
        return true;
    }

    /**
     * Checks one referenced class against the limits of the phase a handler serves: logic of that
     * phase only, data of earlier phases, nothing of a later one.
     */
    private static void checkTarget(JavaClass clazz, JavaClass target, int own, String via, ConditionEvents events) {
        int phase = phaseOf(target);
        if (phase < 0) return;
        boolean data = target.isInterface() || target.isRecord();
        if (phase > own) {
            events.add(SimpleConditionEvent.violated(clazz,
                    via + " references " + target.getName() + " of a later phase"));
        } else if (phase < own && !data) {
            events.add(SimpleConditionEvent.violated(clazz,
                    via + " uses the logic of an earlier phase, " + target.getName()));
        }
    }

    private static String otherPhasesThan(String phase) {
        StringBuilder pattern = new StringBuilder();
        for (String other : PHASE_CLASSES) {
            if (other.equals(phase)) continue;
            if (!pattern.isEmpty()) pattern.append('|');
            pattern.append(other.replace(".", "\\."));
        }
        return pattern.toString();
    }

    private static ArchCondition<JavaClass> haveNoFields() {
        return new ArchCondition<>("have no fields") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (JavaField field : clazz.getFields()) {
                    events.add(SimpleConditionEvent.violated(clazz,
                            clazz.getName() + " has field " + field.getName()));
                }
            }
        };
    }
}
