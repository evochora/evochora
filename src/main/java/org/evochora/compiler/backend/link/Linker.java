package org.evochora.compiler.backend.link;

import org.evochora.compiler.backend.layout.LayoutResult;
import org.evochora.compiler.ir.IrInstruction;
import org.evochora.compiler.ir.IrItem;
import org.evochora.compiler.ir.IrProgram;
import org.evochora.compiler.isa.IInstructionSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Linking pass: resolves symbolic references using the layout result. Parameter binding is collected
 * as metadata only at this stage.
 */
public final class Linker {

	private final LinkingRegistry registry;

	public Linker(LinkingRegistry registry) { this.registry = registry; }

    public IrProgram link(IrProgram program, LayoutResult layout, LinkingContext context) {
        List<IrItem> out = new ArrayList<>();
        // Wir benötigen die ISA-Definitionen, um die Größe der Argumente zu kennen.
        org.evochora.compiler.isa.IInstructionSet isa = new org.evochora.compiler.isa.RuntimeInstructionSetAdapter();

        for (IrItem item : program.items()) {
            if (item instanceof IrInstruction ins) {
                for (ILinkingRule rule : registry.rules()) {
                    ins = rule.apply(ins, context, layout);
                }
                out.add(ins);

                // *** BEGINN DER KORREKTUR: Korrekte Logik zum Zählen der Adressen ***
                context.nextAddress(); // Zuerst für den Opcode selbst zählen.

                Integer opcodeId = isa.getInstructionIdByName(ins.opcode()).orElse(null);
                if (opcodeId != null) {
                    Optional<IInstructionSet.Signature> sigOpt = isa.getSignatureById(opcodeId);
                    if (sigOpt.isPresent()) {
                        // Anhand der Signatur die Größe jedes Arguments bestimmen
                        for (org.evochora.compiler.isa.IInstructionSet.ArgKind kind : sigOpt.get().argumentTypes()) {
                            if (kind == org.evochora.compiler.isa.IInstructionSet.ArgKind.VECTOR || kind == org.evochora.compiler.isa.IInstructionSet.ArgKind.LABEL) {
                                // Vektor- und Label-Argumente belegen mehrere Adressen
                                for (int k = 0; k < org.evochora.runtime.Config.WORLD_DIMENSIONS; k++) {
                                    context.nextAddress();
                                }
                            } else {
                                // Register- und Literal-Argumente belegen eine Adresse
                                context.nextAddress();
                            }
                        }
                    } else {
                        // Fallback, falls eine Instruktion keine Signatur hat
                        int arity = ins.operands() != null ? ins.operands().size() : 0;
                        for (int i = 0; i < arity; i++) context.nextAddress();
                    }
                }
                // *** ENDE DER KORREKTUR ***

            } else {
                out.add(item);
            }
        }
        return new IrProgram(program.programName(), out);
    }
}


