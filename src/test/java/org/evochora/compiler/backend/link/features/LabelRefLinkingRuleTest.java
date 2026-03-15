package org.evochora.compiler.backend.link.features;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.backend.link.LinkingContext;
import org.evochora.compiler.features.label.LabelRefLinkingRule;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.compiler.frontend.semantics.SymbolTable;
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
        rule = new LabelRefLinkingRule();
        context = new LinkingContext(symbolTable, null);
        context.pushAliasChain("TEST");
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
                Collections.emptyMap()
        );

        // And: An instruction with IrLabelRef("FOO") from "test.s"
        IrInstruction input = new IrInstruction(
                "CALL",
                List.of(new IrLabelRef("FOO")),
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

        String[] labelNames = {"INCREMENT", "LOOP_START", "EXIT", "my_proc", "A"};

        for (String labelName : labelNames) {
            String qualifiedName = "TEST." + labelName.toUpperCase();

            // Given: A layout with the module-qualified label
            layout = new LayoutResult(
                    Map.of(0, new int[]{0, 0}),
                    Map.of("0|0", 0),
                    Map.of(qualifiedName, 0),
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
            assertThat(result.operands().get(0)).isInstanceOf(IrTypedImm.class);
            IrTypedImm typedImm = (IrTypedImm) result.operands().get(0);
            assertThat(typedImm.typeName()).isEqualTo("LABELREF");

            long expectedHash = qualifiedName.hashCode() & 0x7FFFF;
            assertThat(typedImm.value())
                    .as("Hash for label '%s' (qualified: '%s') should match runtime expectation", labelName, qualifiedName)
                    .isEqualTo(expectedHash);

            // And: The hash is within the valid range (19 bits, always positive)
            assertThat(typedImm.value()).isGreaterThanOrEqualTo(0);
            assertThat(typedImm.value()).isLessThanOrEqualTo(0x7FFFF);
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
        // Given: A layout WITHOUT the referenced label (module-qualified keys)
        layout = new LayoutResult(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Map.of("TEST.OTHER_LABEL", 5), // Different label
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

    @Test
    void resolvesExportedLabelViaQualifiedName() {
        // Given: A symbol table with an exported label in LIB and an import alias in MAIN
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SymbolTable symbolTable = new SymbolTable(diagnostics);

        String libChain = "LIB";
        String mainChain = "MAIN";
        symbolTable.registerModule(libChain, "lib.s");
        symbolTable.registerModule(mainChain, "main.s");

        // Define the label in LIB module
        symbolTable.setCurrentModule(libChain);
        symbolTable.define(new Symbol("TARGET", new SourceInfo("lib.s", 1, 0), Symbol.Type.LABEL, null, true));

        // Register the import alias: in MAIN, LIB -> libChain
        symbolTable.setCurrentModule(mainChain);
        symbolTable.getModuleScope(mainChain).orElseThrow().imports().put("LIB", libChain);

        // Set up linking context with MAIN alias chain and symbol table
        LabelRefLinkingRule ruleWithExport = new LabelRefLinkingRule();
        LinkingContext exportContext = new LinkingContext(symbolTable, null);
        exportContext.pushAliasChain(mainChain);

        // Given: A layout with module-qualified label "LIB.TARGET"
        layout = new LayoutResult(
                Map.of(10, new int[]{5, 5}),
                Map.of("5|5", 10),
                Map.of("LIB.TARGET", 10),
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        // And: An instruction referencing "LIB.TARGET" from main.s
        SourceInfo mainSource = new SourceInfo("main.s", 1, 0);
        IrInstruction input = new IrInstruction(
                "JMPI",
                List.of(new IrLabelRef("LIB.TARGET")),
                mainSource
        );

        // When: The rule is applied
        IrInstruction result = ruleWithExport.apply(input, exportContext, layout);

        // Then: The qualified label is resolved to the hash of "LIB.TARGET"
        assertThat(result.operands()).hasSize(1);
        assertThat(result.operands().get(0)).isInstanceOf(IrTypedImm.class);

        IrTypedImm typedImm = (IrTypedImm) result.operands().get(0);
        assertThat(typedImm.typeName()).isEqualTo("LABELREF");
        long expectedHash = "LIB.TARGET".hashCode() & 0x7FFFF;
        assertThat(typedImm.value()).isEqualTo(expectedHash);
    }

    @Test
    void doesNotResolveNonExportedLabelViaQualifiedName() {
        // Given: A symbol table with a NON-exported label in LIB
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        SymbolTable symbolTable = new SymbolTable(diagnostics);

        String libChain = "LIB";
        String mainChain = "MAIN";
        symbolTable.registerModule(libChain, "lib.s");
        symbolTable.registerModule(mainChain, "main.s");

        // Define the label in LIB module (NOT exported)
        symbolTable.setCurrentModule(libChain);
        symbolTable.define(new Symbol("PRIVATE", new SourceInfo("lib.s", 1, 0), Symbol.Type.LABEL));

        // Register the import alias
        symbolTable.setCurrentModule(mainChain);
        symbolTable.getModuleScope(mainChain).orElseThrow().imports().put("LIB", libChain);

        LabelRefLinkingRule ruleWithNonExport = new LabelRefLinkingRule();
        LinkingContext nonExportContext = new LinkingContext(symbolTable, null);
        nonExportContext.pushAliasChain(mainChain);

        // Layout contains the label
        layout = new LayoutResult(
                Map.of(10, new int[]{5, 5}),
                Map.of("5|5", 10),
                Map.of("PRIVATE", 10),
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        // Instruction referencing "LIB.PRIVATE" from main.s
        SourceInfo mainSource = new SourceInfo("main.s", 1, 0);
        IrInstruction input = new IrInstruction(
                "JMPI",
                List.of(new IrLabelRef("LIB.PRIVATE")),
                mainSource
        );

        // When: The rule is applied
        IrInstruction result = ruleWithNonExport.apply(input, nonExportContext, layout);

        // Then: The label is NOT resolved (stays as IrLabelRef) because it's not exported
        assertThat(result.operands().get(0)).isInstanceOf(IrLabelRef.class);
    }
}
