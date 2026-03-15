package org.evochora.compiler.frontend.irgen.converters;

import org.evochora.compiler.frontend.irgen.IAstNodeToIrConverter;
import org.evochora.compiler.frontend.irgen.IrGenContext;
import org.evochora.compiler.frontend.parser.features.require.RequireNode;

/**
 * IR converter for {@code .REQUIRE} directives. This is intentionally a no-op:
 * require declarations are metadata for the module system and do not produce IR items.
 */
public class RequireNodeConverter implements IAstNodeToIrConverter<RequireNode> {

    @Override
    public void convert(RequireNode node, IrGenContext ctx) {
        // .REQUIRE is a dependency declaration and does not produce IR items
    }
}
