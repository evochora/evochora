package org.evochora.compiler.ir;

import org.evochora.compiler.ir.placement.IPlacementArgument;
import java.util.List;
import java.util.Map;

/**
 * Small, typed value system used for directive arguments. Designed to be
 * easily serializable and extensible without leaking Object.
 */
public sealed interface IrValue permits IrValue.Int64, IrValue.Str, IrValue.Bool, IrValue.Vector, IrValue.ListVal, IrValue.MapVal, IrValue.PlacementListVal {
	/**
	 * Represents a 64-bit integer value.
	 * @param value The long value.
	 */
	record Int64(long value) implements IrValue {}

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
	 * Represents a vector of integer components.
	 * @param components The integer array representing the vector components.
	 */
	record Vector(int[] components) implements IrValue {}

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
