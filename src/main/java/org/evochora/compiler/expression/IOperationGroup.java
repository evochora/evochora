package org.evochora.compiler.expression;

import java.util.List;

/**
 * A set of operations a feature registers together, such as the arithmetic operators, holding
 * the rule for the kinds they share in one place.
 */
public interface IOperationGroup {

    /**
     * Returns the operations of the group, each to be registered on its own.
     *
     * @return The operations.
     */
    List<IOperation> operations();
}
