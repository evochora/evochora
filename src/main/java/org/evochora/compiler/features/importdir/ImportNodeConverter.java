package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.features.importdir.ImportNode;

/**
 * IR converter for {@code .IMPORT} directives. This is intentionally a no-op:
 * the imported module's IR items are merged into the program during the
 * multi-module IR merge step in the Compiler, not during per-module IR generation.
 */
public class ImportNodeConverter implements IAstNodeToIrConverter<ImportNode> {

    @Override
    public void convert(ImportNode node, IrGenContext ctx) {
        // .IMPORT does not produce IR items directly
    }
}
