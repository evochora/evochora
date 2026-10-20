package org.evochora.compiler.model.ir;

import org.evochora.compiler.model.ir.placement.IPlacementArgument;
import org.evochora.compiler.model.expression.Value;

import java.util.List;
import java.util.Map;

/**
 * Small, typed value system used for directive arguments. Designed to be
 * easily serializable and extensible without leaking Object.
 */
public sealed interface IrValue permits IrValue.Literal, IrValue.Str, IrValue.Bool, IrValue.ListVal, IrValue.MapVal, IrValue.PlacementListVal {
	/**
	 * A literal value: a number, a typed value or a vector, as {@link Value} carries it.
	 * @param value The value.
	 */
	record Literal(Value value) implements IrValue {}

	/**
	 * Represents a string value.
	 * @param value The string value.
	 */
	record Str(String value) implements IrValue {}

	/**
	 * Represents a boolean value.
	 * @param value The boolean value.
	 */
	record Bool(boolean value) implements IrValue {}


	/**
	 * Represents a list of IrValue elements.
	 * @param elements The list of IrValue elements.
	 */
	record ListVal(List<IrValue> elements) implements IrValue {}

	/**
	 * Represents a map of string keys to IrValue values.
	 * @param entries The map of string keys to IrValue values.
	 */
	record MapVal(Map<String, IrValue> entries) implements IrValue {}

	/**
	 * Represents a list of placement arguments.
	 * @param placements The list of placement arguments.
	 */
	record PlacementListVal(List<IPlacementArgument> placements) implements IrValue {}
}
