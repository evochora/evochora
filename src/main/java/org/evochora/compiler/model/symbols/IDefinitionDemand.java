package org.evochora.compiler.model.symbols;

import java.util.List;

/**
 * Analyzes a definition the moment a name needs its value, before the walk of phase 4 reaches
 * its line. The symbol table calls it when a chain of names ends at a definition whose value is
 * a computation that has neither been evaluated nor failed; the phase that set it runs the
 * handler of the defining node in the scope and placement the definition stands in, which
 * records what it made of the computation, so that the table finds the value when it looks
 * again. A definition that is asked for while its own handler runs is circular; the phase
 * reports that and records nothing, and the table finds no value.
 */
@FunctionalInterface
public interface IDefinitionDemand {

    /**
     * Analyzes a definition now.
     *
     * @param definition The symbol of the definition; its node is the defining node.
     * @param scope      The scope the definition is held in, from which its handler resolves.
     * @param through    The names the lookup followed to reach the definition, as written,
     *                   the definition's own name last; for the message about a circle.
     */
    void analyze(Symbol definition, SymbolTable.Scope scope, List<String> through);
}
