package org.evochora.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.evochora.compiler.ICompilerFeature;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.backend.emit.IEmissionContributor;
import org.evochora.compiler.backend.emit.IEmissionRule;
import org.evochora.compiler.backend.layout.ILayoutDirectiveHandler;
import org.evochora.compiler.backend.link.ILinkingDirectiveHandler;
import org.evochora.compiler.backend.link.ILinkingRule;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrItem;
import org.evochora.compiler.model.ir.IrOperand;
import org.evochora.compiler.model.ir.IrValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Enforces the structure of the compiler described in the <em>Compiler</em> section of
 * {@code AGENTS.md}: a feature-agnostic core, features that know neither each other nor any
 * state, three data formats that do not reach into one another, and phases that meet only in
 * {@code Compiler}.
 * <p>
 * Each rule here is the enforceable form of one sentence of that section. A rule fires naming
 * the classes involved, and the ways forward are the same as for the package graph: withdraw the
 * dependency, or edit this class and let the edit be reviewed. The rules are kept to what the
 * section states, so that a new one is a decision about the architecture, not a reflex.
 */
@Tag("unit")
class CompilerArchitectureRulesTest {

    /** Package holding the whole compiler. */
    private static final String COMPILER = "org.evochora.compiler";

    /** Package holding one sub-package per feature. */
    private static final String FEATURES = COMPILER + ".features";

    /** The classes that run the phases of the pipeline, in pipeline order. */
    private static final String[] PHASE_CLASSES = {
            COMPILER + ".frontend.module.DependencyScanner",
            COMPILER + ".frontend.lexer.Lexer",
            COMPILER + ".frontend.preprocessor.PreProcessor",
            COMPILER + ".frontend.parser.Parser",
            COMPILER + ".frontend.semantics.SemanticAnalyzer",
            COMPILER + ".frontend.tokenmap.TokenMapGenerator",
            COMPILER + ".frontend.postprocess.AstPostProcessor",
            COMPILER + ".frontend.irgen.IrGenerator",
            COMPILER + ".backend.layout.LayoutEngine",
            COMPILER + ".backend.link.Linker",
            COMPILER + ".backend.emit.Emitter",
    };

    /** The packages holding the phases of the pipeline, in pipeline order. */
    private static final String[] PHASE_PACKAGES = {
            COMPILER + ".frontend.module..",
            COMPILER + ".frontend.lexer..",
            COMPILER + ".frontend.preprocessor..",
            COMPILER + ".frontend.parser..",
            COMPILER + ".frontend.semantics..",
            COMPILER + ".frontend.tokenmap..",
            COMPILER + ".frontend.postprocess..",
            COMPILER + ".frontend.irgen..",
            COMPILER + ".backend.layout..",
            COMPILER + ".backend.link..",
            COMPILER + ".backend.emit..",
    };

    private static JavaClasses compilerClasses;

    /**
     * Imports the compiled production classes of the compiler once for all rules in this class,
     * with the same exclusions as {@link PackageDependencyRulesTest}.
     */
    @BeforeAll
    static void importCompilerClasses() {
        compilerClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("test-fixtures"))
                .withImportOption(location -> !location.contains("-jmh"))
                .importPackages(COMPILER);
    }

    /**
     * The core never references a feature: the only place that knows which features exist is
     * {@code StandardFeatures}.
     * <p>
     * Features depend on phases, not the other way round. A phase, a context, the symbol table
     * or a data format that named a feature class would have to change whenever that feature
     * does, and a new feature could not be added without touching the core.
     */
    @Test
    void onlyStandardFeaturesReferencesFeatures() {
        noClasses().that().resideInAPackage(COMPILER + "..")
                .and().resideOutsideOfPackage(FEATURES + "..")
                .and().doNotBelongToAnyOf(StandardFeatures.class)
                .should().dependOnClassesThat().resideInAPackage(FEATURES + "..")
                .check(compilerClasses);
    }

    /**
     * No feature references another feature.
     * <p>
     * A feature is the unit of change. A handler that needs another feature's work reads it from
     * the phase's input data, never from the other feature's classes; otherwise a change to one
     * feature spreads into the next.
     */
    @Test
    void featuresDoNotReferenceEachOther() {
        slices().matching(FEATURES + ".(*)..")
                .should().notDependOnEachOther()
                .check(compilerClasses);
    }

    /**
     * Features have no state: a feature class only registers its handlers and holds nothing.
     * <p>
     * Compilation data flows through the phase contexts. A field on a feature would be state that
     * survives a compilation, or a constructor parameter that has to be supplied by whoever
     * instantiates the feature.
     */
    @Test
    void featuresHaveNoFields() {
        classes().that().implement(ICompilerFeature.class)
                .should(haveNoFields())
                .check(compilerClasses);
    }

    /**
     * Static fields of a feature hold constants, not state.
     * <p>
     * A static field that changes carries state from one compilation into the next, and the
     * artifact of a source then depends on what was compiled before it in the same process. The
     * field has to be final, and it may not hold an atomic, the usual shape of a counter. A
     * collection is not examined: its type does not tell whether it can change.
     */
    @Test
    void featureStaticFieldsAreConstants() {
        fields().that().areDeclaredInClassesThat().resideInAPackage(FEATURES + "..")
                .and().areStatic()
                .should().beFinal()
                .andShould().notHaveRawType(resideInAPackage("java.util.concurrent.atomic.."))
                .check(compilerClasses);
    }

    /**
     * A phase package is flat: its orchestrator, registries, contexts and handler interfaces
     * lie side by side, and it has no sub-packages.
     * <p>
     * A handler interface lives in the package of the phase it serves, and there is no separate
     * package for interfaces. A sub-package of a phase is such a package under another name, and
     * it splits the phase's surface across two places for no reason the phase has.
     */
    @Test
    void phasePackagesAreFlat() {
        for (String phasePackage : PHASE_PACKAGES) {
            String flat = phasePackage.substring(0, phasePackage.length() - 2);
            classes().that().resideInAPackage(phasePackage)
                    .should().resideInAPackage(flat)
                    .check(compilerClasses);
        }
    }

    /**
     * A top-level interface carries the {@code I} prefix, except the roots of the data formats.
     * <p>
     * Handler, registration and context interfaces are recognisable by name. The five exceptions
     * are the types every AST node, AST operand, IR item, IR operand and IR value implements;
     * they name a data format, not a role, and are listed here so that a sixth exception is a
     * decision. A nested interface is named by its owner, as in
     * {@code IInstructionSet.Signature}, and is not covered.
     */
    @Test
    void topLevelInterfacesCarryThePrefix() {
        classes().that().areInterfaces().and().areTopLevelClasses()
                .and().doNotBelongToAnyOf(AstNode.class, OperandNode.class, IrItem.class, IrOperand.class, IrValue.class)
                .should().haveNameMatching(".*\\.I[A-Z][A-Za-z0-9]*")
                .check(compilerClasses);
    }

    /**
     * The backend sees the IR and the instruction set, nothing of the frontend.
     * <p>
     * From phase 7 on the IR is the single source of truth: what is not in the IR does not
     * exist for the backend. Neither a backend package nor a handler a feature registers for a
     * backend phase may read the symbol table, the AST or the tokens. A handler that needs
     * something from there says that the IR is missing it, and then it belongs into the IR.
     */
    @Test
    void backendReadsOnlyTheIrAndTheInstructionSet() {
        noClasses().that().resideInAPackage(COMPILER + ".backend..")
                .or().implement(ILayoutDirectiveHandler.class)
                .or().implement(ILinkingRule.class)
                .or().implement(ILinkingDirectiveHandler.class)
                .or().implement(IEmissionRule.class)
                .or().implement(IEmissionContributor.class)
                .should().dependOnClassesThat().resideInAnyPackage(
                        COMPILER + ".frontend..",
                        COMPILER + ".model.ast..",
                        COMPILER + ".model.token..",
                        COMPILER + ".model.symbols..")
                .check(compilerClasses);
    }

    /**
     * No phase decides by the kind of a symbol; only the token map names the kinds, when it
     * reports them to the debugger.
     * <p>
     * The kinds in {@code Symbol.Type} are those of the features: a label, a procedure, a
     * register alias. A phase that branches on them knows those features. What a phase may do
     * with a symbol, it learns from the capabilities of the node that defined it.
     */
    @Test
    void phasesDoNotBranchOnSymbolKinds() {
        noClasses().that().resideInAnyPackage(COMPILER + ".frontend..", COMPILER + ".backend..")
                .and().resideOutsideOfPackage(COMPILER + ".frontend.tokenmap..")
                .should().dependOnClassesThat().haveFullyQualifiedName(COMPILER + ".model.symbols.Symbol$Type")
                .check(compilerClasses);
    }

    /**
     * AST nodes and IR items are records: pure data, immutable, equal by content.
     * <p>
     * A record says at a glance what a class has to be read for. The one exception is
     * {@code IrInstruction}: a feature may subtype it to carry operands beyond the main ones,
     * which a record cannot do, and {@code IrCallInstruction} does.
     */
    @Test
    void dataTypesAreRecords() {
        classes().that().implement(AstNode.class).or().implement(IrItem.class)
                .and().areNotInterfaces()
                .and().areNotAssignableTo(IrInstruction.class)
                .should().beRecords()
                .check(compilerClasses);
    }

    /**
     * The model package holds nothing but its sub-packages: the three data formats and the
     * symbol table.
     * <p>
     * A class placed directly in {@code model} is neither a data format nor a symbol, and what
     * is not data has no place among the data formats. The helpers that walk the AST for a
     * phase live with that phase.
     */
    @Test
    void modelPackageHoldsOnlyItsSubPackages() {
        noClasses().should().resideInAPackage(COMPILER + ".model")
                .check(compilerClasses);
    }

    /**
     * The three data formats are separate: token, AST and IR types do not reference one another.
     * <p>
     * An AST node carries a token's {@code SourceInfo}, never the token, and an IR item carries
     * nothing of the AST it was made from. The rule is checked on the types themselves, wherever
     * they live, because features define AST nodes and IR items of their own.
     */
    @Test
    void dataFormatsDoNotReferenceEachOther() {
        noClasses().that().resideInAPackage(COMPILER + ".model.token..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        COMPILER + ".model.ast..", COMPILER + ".model.ir..")
                .check(compilerClasses);
        noClasses().that().implement(AstNode.class).or().resideInAPackage(COMPILER + ".model.ast..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        COMPILER + ".model.token..", COMPILER + ".model.ir..")
                .check(compilerClasses);
        noClasses().that().implement(IrItem.class).or().resideInAPackage(COMPILER + ".model.ir..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        COMPILER + ".model.token..", COMPILER + ".model.ast..")
                .check(compilerClasses);
    }

    /**
     * Phases never call other phases: {@code Compiler} is the only class that knows the order.
     * <p>
     * A phase receives its input from the compiler and returns its result to it. A phase that
     * reached into another phase would fix the order of the pipeline in a second place.
     */
    @Test
    void phasesDoNotReferenceEachOther() {
        for (String phase : PHASE_CLASSES) {
            noClasses().that().haveFullyQualifiedName(phase)
                    .should().dependOnClassesThat().haveNameMatching(otherPhasesThan(phase))
                    .check(compilerClasses);
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
    @Test
    void phasePackagesReferenceOnlyEarlierPhases() {
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
        layers.check(compilerClasses);
    }

    private static String otherPhasesThan(String phase) {
        StringBuilder pattern = new StringBuilder();
        for (String other : PHASE_CLASSES) {
            if (other.equals(phase)) continue;
            if (pattern.length() > 0) pattern.append('|');
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
