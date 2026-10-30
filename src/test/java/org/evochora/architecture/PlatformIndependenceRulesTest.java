package org.evochora.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import java.util.Set;

/**
 * Enforces that the runtime and the compiler compute no floating-point function whose result may
 * differ between platforms.
 * <p>
 * The specification of {@link Math} lets these functions deviate in the last bit, and HotSpot
 * computes them with different routines on different architectures. In the runtime such a bit can
 * tip a decision, after which two runs with the same seed and configuration take different courses
 * on different machines; the compiler's output decides the run as well. {@link StrictMath} computes
 * the same result on every platform and is the way to use these functions there. The functions
 * {@link Math} specifies exactly, such as {@code sqrt}, {@code fma} and {@code round}, remain allowed.
 */
@AnalyzeClasses(packages = "org.evochora", importOptions = {ImportOption.DoNotIncludeTests.class, ExcludeTestFixturesAndJmh.class})
@ArchTag("integration")
class PlatformIndependenceRulesTest {

    /** The functions of {@link Math} whose result may differ between platforms. */
    private static final Set<String> PLATFORM_DEPENDENT_FUNCTIONS = Set.of(
            "pow", "exp", "expm1", "log", "log10", "log1p",
            "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
            "sinh", "cosh", "tanh", "hypot", "cbrt");

    @ArchTest
    static void runtimeAndCompilerComputeTheSameOnEveryPlatform(JavaClasses classes) {
        noClasses().that().resideInAnyPackage("org.evochora.runtime..", "org.evochora.compiler..")
                .should().callMethodWhere(platformDependentFunction())
                .check(classes);
    }

    private static DescribedPredicate<JavaMethodCall> platformDependentFunction() {
        return new DescribedPredicate<>("a function of Math whose result may differ between platforms") {
            @Override
            public boolean test(JavaMethodCall call) {
                return call.getTargetOwner().isEquivalentTo(Math.class)
                        && PLATFORM_DEPENDENT_FUNCTIONS.contains(call.getName());
            }
        };
    }
}
