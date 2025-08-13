package org.evochora.compiler.core.directives;

import org.evochora.compiler.core.phases.CompilerPhase;
import org.evochora.compiler.core.phases.Parser;
import org.evochora.compiler.core.phases.ParsingContext;
import org.evochora.compiler.core.ast.AstNode;
import org.evochora.compiler.core.ast.OrgNode;
import org.evochora.compiler.core.ast.VectorLiteralNode;

public class OrgDirectiveHandler implements IDirectiveHandler {
    @Override public CompilerPhase getPhase() { return CompilerPhase.PARSING; }
    @Override public AstNode parse(ParsingContext context) {
        context.advance(); // .ORG konsumieren
        Parser parser = (Parser) context;
        AstNode vector = parser.expression();
        if (!(vector instanceof VectorLiteralNode)) {
            context.getDiagnostics().reportError("Expected a vector literal after .ORG.", "Unknown", context.peek().line());
        }
        return new OrgNode(vector);
    }
}
