package org.evochora.compiler.features.importdir;

import org.evochora.compiler.frontend.DirectiveLine;
import org.evochora.compiler.frontend.lexer.IDependencyScanContext;
import org.evochora.compiler.frontend.lexer.IDependencyScanHandler;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.util.SourceRootResolver;

import java.io.IOException;
import java.util.List;

/**
 * Lexer-phase handler for {@code .IMPORT "path" AS ALIAS …}: reads the module file as a
 * placement of its own, under the importing placement's chain followed by the alias, and
 * records the dependency.
 * <p>
 * Every line that names a module file is followed, whatever comes after the path, so the file is
 * loaded even when the directive is malformed and the parser can report the malformation. An
 * {@code EXPORT} before the word is accepted so that the line is read as an import; whether the
 * import is passed on is taken from the parser's node, not from here. A directive without an
 * alias names no dependency; its tokens are still needed so the phase that reads the directive
 * can report what is wrong with it.
 */
public class ImportDependencyScanHandler implements IDependencyScanHandler {

    @Override
    public void handle(DirectiveLine line, IDependencyScanContext ctx) {
        Token before = line.before();
        if (before != null && !(before.type() == TokenType.IDENTIFIER && "EXPORT".equalsIgnoreCase(before.text()))) {
            return;
        }
        List<Token> operands = line.operands();
        if (operands.isEmpty() || operands.get(0).type() != TokenType.STRING) {
            return;
        }
        String path = (String) operands.get(0).value();
        String resolvedPath;
        try {
            resolvedPath = ctx.resolve(path);
        } catch (SourceRootResolver.UnknownPrefixException e) {
            ctx.reportError(e.getMessage());
            return;
        }
        // The placement's chain is formed as the preprocessor forms it: the importing placement's
        // chain followed by the alias.
        String aliasChain = ctx.placementChain();
        if (operands.size() >= 3 && operands.get(1).type() == TokenType.IDENTIFIER
                && "AS".equalsIgnoreCase(operands.get(1).text())
                && operands.get(2).type() == TokenType.IDENTIFIER) {
            String alias = operands.get(2).text();
            aliasChain = childChain(ctx.placementChain(), alias);
            ctx.addDependency(new ImportDependencyInfo(path, alias, resolvedPath, aliasChain));
        }
        try {
            ctx.scanNestedModule(resolvedPath, aliasChain);
        } catch (IOException e) {
            ctx.reportError("Module file not found: " + path);
        }
    }

    private static String childChain(String parentChain, String alias) {
        String aliasUpper = alias.toUpperCase();
        return (parentChain == null || parentChain.isEmpty()) ? aliasUpper : parentChain + "." + aliasUpper;
    }
}
