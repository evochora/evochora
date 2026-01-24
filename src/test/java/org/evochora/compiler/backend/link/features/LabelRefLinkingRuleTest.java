package org.evochora.compiler.backend.link.features;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.SymbolTable;
import org.evochora.compiler.ir.IrImm;
import org.evochora.compiler.ir.IrInstruction;
import org.evochora.compiler.ir.IrLabelRef;
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
 * Verifies that label references are correctly converted to hash values
 * that match what the runtime expects.
 */
@Tag("unit")
class LabelRefLinkingRuleTest {

    private LabelRefLinkingRule rule;
    private LinkingContext context;
    private LayoutResult layout;
    private SourceInfo dummySource;

    @BeforeEach
    void setUp() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SymbolTable symbolTable = new SymbolTable(diagnostics);
        rule = new LabelRefLinkingRule(symbolTable);
        context = new LinkingContext();
        dummySource = new SourceInfo("test.s", 1, 0);
    }

    @Test
    void convertsLabelRefToHashValue() {
        // Given: A layout with label "FOO" at address 10
        layout = new LayoutResult(
                Map.of(10, new int[]{5, 5}),
                Map.of("5|5", 10),
                Map.of("FOO", 10),
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        // And: An instruction with IrLabelRef("FOO")
        IrInstruction input = new IrInstruction(
                "CALL",
                List.of(new IrLabelRef("FOO")),
                dummySource
        );

        // When: The rule is applied
        IrInstruction result = rule.apply(input, context, layout);

        // Then: The IrLabelRef is converted to IrImm with the correct hash
        assertThat(result.operands()).hasSize(1);
        assertThat(result.operands().get(0)).isInstanceOf(IrImm.class);

        long expectedHash = "FOO".hashCode() & 0x7FFFF; // 19-bit, always positive
        long actualHash = ((IrImm) result.operands().get(0)).value();
        assertThat(actualHash).isEqualTo(expectedHash);
    }

    @Test
    void hashIsConsistentWithRuntimeExpectation() {
        // This test verifies that the hash generation in the compiler
        // matches what the runtime's LabelIndex expects.

        String[] labelNames = {"INCREMENT", "LOOP_START", "EXIT", "my_proc", "A"};

        for (String labelName : labelNames) {
            // Given: A layout with the label
            layout = new LayoutResult(
                    Map.of(0, new int[]{0, 0}),
                    Map.of("0|0", 0),
                    Map.of(labelName, 0),
                    Collections.emptyMap(),
                    Collections.emptyMap()
            );

            IrInstruction input = new IrInstruction(
                    "JMPI",
                    List.of(new IrLabelRef(labelName)),
                    dummySource
            );

            // When: The rule is applied
            IrInstruction result = rule.apply(input, context, layout);

            // Then: The hash matches the expected formula (19-bit, always positive)
            long expectedHash = labelName.hashCode() & 0x7FFFF;
            long actualHash = ((IrImm) result.operands().get(0)).value();

            assertThat(actualHash)
                    .as("Hash for label '%s' should match runtime expectation", labelName)
                    .isEqualTo(expectedHash);

            // And: The hash is within the valid range (19 bits, always positive)
            assertThat(actualHash).isGreaterThanOrEqualTo(0);
            assertThat(actualHash).isLessThanOrEqualTo(0x7FFFF);
        }
    }

    @Test
    void doesNotModifyInstructionWithoutLabelRef() {
        layout = new LayoutResult(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap()
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
                Map.of("OTHER_LABEL", 5), // Different label
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        IrInstruction input = new IrInstruction(
                "CALL",
                List.of(new IrLabelRef("UNKNOWN")),
                dummySource
        );

        // When: The rule is applied
        IrInstruction result = rule.apply(input, context, layout);

        // Then: The IrLabelRef is NOT converted (stays as-is for error handling later)
        assertThat(result.operands().get(0)).isInstanceOf(IrLabelRef.class);
    }
}
