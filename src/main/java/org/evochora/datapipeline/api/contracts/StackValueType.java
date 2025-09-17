package org.evochora.datapipeline.api.contracts;

/**
 * Discriminator for the type of value held in a StackValue object.
 */
public enum StackValueType {
    /**
     * The value is a 32-bit integer literal.
     */
    LITERAL,

    /**
     * The value is an n-dimensional position vector.
     */
    POSITION
}
