package org.evochora.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
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
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ir.IrItem;
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
