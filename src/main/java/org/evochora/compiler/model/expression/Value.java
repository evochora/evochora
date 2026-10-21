package org.evochora.compiler.model.expression;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A value the compiler computes with: the one literal of the AST, the IR and the expressions.
 * Five kinds, each a record of its own, so that a phase can switch over them exhaustively; a
 * number is 64 bits wide throughout the compiler, and whoever turns a value into a cell, a
 * coordinate or a count checks its range there.
 */
public sealed interface Value permits Value.Number, Value.Typed, Value.Vector, Value.Type, Value.Position {

    /**
     * Returns the kind of this value.
     *
     * @return The kind, one per record.
     */
    Kind kind();

    /**
     * Writes the value as source would: a number in decimal, a typed value as {@code TYPE:number},
     * a vector and a position as their components joined by {@code |}, a type by its name.
     *
     * @return The text of the value.
     */
    String text();

    /**
     * A 64-bit integer, the value of a bare number.
     *
     * @param value The number.
     */
    record Number(long value) implements Value {
        @Override
        public Kind kind() {
            return Kind.NUMBER;
        }

        @Override
        public String text() {
            return Long.toString(value);
        }
    }

    /**
     * A molecule type with a number, the value of a typed literal such as {@code DATA:5}.
     *
     * @param type   The name of the molecule type, as the instruction set knows it.
     * @param number The number.
     */
    record Typed(String type, long number) implements Value {
        @Override
        public Kind kind() {
            return Kind.TYPED;
        }

        @Override
        public String text() {
            return type + ":" + number;
        }
    }

    /**
     * A list of numbers, one per dimension, the value of a vector literal such as {@code 1|0}.
     *
     * @param components The components, in order; copied, so that the record is immutable.
     */
    record Vector(List<Long> components) implements Value {
        /**
         * Copies the components.
         */
        public Vector {
            components = List.copyOf(components);
        }

        @Override
        public Kind kind() {
            return Kind.VECTOR;
        }

        @Override
        public String text() {
            return join(components);
        }
    }

    /**
     * A molecule type on its own, the value of a type name used as a value.
     *
     * @param name The name of the molecule type, as the instruction set knows it.
     */
    record Type(String name) implements Value {
        @Override
        public Kind kind() {
            return Kind.TYPE;
        }

        @Override
        public String text() {
            return name;
        }
    }

    /**
     * A place in the program: a vector from the program's origin, the origin of the main file.
     *
     * @param components The components, in order; copied, so that the record is immutable.
     */
    record Position(List<Long> components) implements Value {
        /**
         * Copies the components.
         */
        public Position {
            components = List.copyOf(components);
        }

        @Override
        public Kind kind() {
            return Kind.POSITION;
        }

        @Override
        public String text() {
            return join(components);
        }
    }

    /**
     * Joins components with {@code |}, the way a vector is written.
     */
    private static String join(List<Long> components) {
        return components.stream().map(String::valueOf).collect(Collectors.joining("|"));
    }
}
