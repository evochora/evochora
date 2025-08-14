package org.evochora.compiler.ir;

import org.evochora.compiler.api.SourceInfo;

import java.util.Map;

/**
 * Generic, plugin-friendly directive. Namespaced for extensibility. Arguments
 * use a small typed value system to avoid Object-typed maps.
 */
public record IrDirective(String namespace, String name, Map<String, IrValue> args, SourceInfo source) implements IrItem {}


