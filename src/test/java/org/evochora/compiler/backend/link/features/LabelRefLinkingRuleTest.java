package org.evochora.compiler.backend.link.features;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.features.label.LabelRefLinkingRule;
import org.evochora.compiler.model.ir.IrImm;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrLabelRef;
import org.evochora.compiler.model.ir.IrTypedImm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for LabelRefLinkingRule.
 * <p>
 * A label reference reaches the linker under the label's qualified name, as the frontend
 * resolved it. The rule turns it into the value the runtime matches jumps against.
 */
@Tag("unit")
class LabelRefLinkingRuleTest {

    private LabelRefLinkingRule rule;
    private LinkingContext context;
    private LayoutResult layout;
    private SourceInfo dummySource;

    @BeforeEach
    void setUp() {
        rule = new LabelRefLinkingRule();
        context = new LinkingContext(null);
        dummySource = new SourceInfo("test.s", 1, 0);
    }

    @Test
    void convertsLabelRefToHashValue() {
        // Given: A layout with module-qualified label "TEST.FOO" at address 10
        layout = new LayoutResult(
                Map.of(10, new int[]{5, 5}),
                Map.of("5|5", 10),
                Map.of("TEST.FOO", 10),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyList()
        );

        // And: An instruction referring to the label under its qualified name
        IrInstruction input = new IrInstruction(
                "CALL",
                List.of(new IrLabelRef("TEST.FOO")),
                dummySource
        );

        // When: The rule is applied
        IrInstruction result = rule.apply(input, context, layout);

        // Then: The IrLabelRef is converted to IrTypedImm("LABELREF", hash)
        assertThat(result.operands()).hasSize(1);
        assertThat(result.operands().get(0)).isInstanceOf(IrTypedImm.class);

        IrTypedImm typedImm = (IrTypedImm) result.operands().get(0);
        assertThat(typedImm.typeName()).isEqualTo("LABELREF");
        long expectedHash = "TEST.FOO".hashCode() & 0x7FFFF;
        assertThat(typedImm.value()).isEqualTo(expectedHash);
    }

    @Test
    void hashIsConsistentWithRuntimeExpectation() {
        // This test verifies that the hash generation in the compiler
        // matches what the runtime's LabelIndex expects.

        String[] labelNames = {"INCREMENT", "LOOP_START", "EXIT", "MY_PROC", "A"};

        for (String labelName : labelNames) {
            String qualifiedName = "TEST." + labelName;

            // Given: A layout with the module-qualified label
            layout = new LayoutResult(
                    Map.of(0, new int[]{0, 0}),
                    Map.of("0|0", 0),
                    Map.of(qualifiedName, 0),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyList()
            );

            IrInstruction input = new IrInstruction(
                    "JMPI",
                    List.of(new IrLabelRef(qualifiedName)),
                    dummySource
            );

            // When: The rule is applied
            IrInstruction result = rule.apply(input, context, layout);

            // Then: The hash matches the expected formula (19-bit, always positive)
            assertThat(result.operands().get(0)).isInstanceOf(IrTypedImm.class);
            IrTypedImm typedImm = (IrTypedImm) result.operands().get(0);
            assertThat(typedImm.typeName()).isEqualTo("LABELREF");

            long expectedHash = qualifiedName.hashCode() & 0x7FFFF;
            assertThat(typedImm.value())
                    .as("Hash for label '%s' should match runtime expectation", qualifiedName)
                    .isEqualTo(expectedHash);

            // And: The hash is within the valid range (19 bits, always positive)
            assertThat(typedImm.value()).isGreaterThanOrEqualTo(0);
            assertThat(typedImm.value()).isLessThanOrEqualTo(0x7FFFF);
        }
    }

    @Test
    void resolvesSyntheticLabelUnderItsOwnName() {
        // Given: A bridge label created by a marshalling rule, which carries no module name
        layout = new LayoutResult(
                Map.of(3, new int[]{3, 0}),
                Map.of("3|0", 3),
                Map.of("_safe_call_0", 3),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyList()
        );

        IrInstruction input = new IrInstruction(
                "JMPI",
                List.of(new IrLabelRef("_safe_call_0")),
                dummySource
        );

        // When: The rule is applied
        IrInstruction result = rule.apply(input, context, layout);

        // Then: The reference is resolved exactly as written
        assertThat(result.operands().get(0)).isInstanceOf(IrTypedImm.class);
        assertThat(((IrTypedImm) result.operands().get(0)).value())
                .isEqualTo("_safe_call_0".hashCode() & 0x7FFFF);
    }

    @Test
    void doesNotModifyInstructionWithoutLabelRef() {
        layout = new LayoutResult(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyList()
        );

        // Given: An instruction without IrLabelRef
        IrInstruction input = new IrInstruction(
                "ADDI",
                List.of(new IrImm(42)),
                dummySource
        );

        // When: The rule is applied
        IrInstruction result = rule.apply(input, context, layout);

        // Then: The instruction is unchanged
        assertThat(result).isSameAs(input);
    }

    @Test
    void doesNotConvertUnknownLabel() {
        // Given: A layout WITHOUT the referenced label
        layout = new LayoutResult(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Map.of("TEST.OTHER_LABEL", 5), // Different label
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyList()
        );

        IrInstruction input = new IrInstruction(
                "CALL",
                List.of(new IrLabelRef("TEST.UNKNOWN")),
                dummySource
        );

        // When: The rule is applied
        IrInstruction result = rule.apply(input, context, layout);

        // Then: The IrLabelRef is NOT converted (stays as-is for error handling later)
        assertThat(result.operands().get(0)).isInstanceOf(IrLabelRef.class);
    }
}
