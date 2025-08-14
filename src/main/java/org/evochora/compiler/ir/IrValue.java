package org.evochora.compiler.ir;

import java.util.List;
import java.util.Map;

/**
 * Small, typed value system used for directive arguments. Designed to be
 * easily serializable and extensible without leaking Object.
 */
public sealed interface IrValue permits IrValue.Int64, IrValue.Str, IrValue.Bool, IrValue.Vector, IrValue.ListVal, IrValue.MapVal {

	record Int64(long value) implements IrValue {}
	record Str(String value) implements IrValue {}
	record Bool(boolean value) implements IrValue {}
	record Vector(int[] components) implements IrValue {}
	record ListVal(List<IrValue> elements) implements IrValue {}
	record MapVal(Map<String, IrValue> entries) implements IrValue {}
}


