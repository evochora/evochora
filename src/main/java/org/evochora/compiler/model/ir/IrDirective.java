package org.evochora.compiler.model.ir;

import org.evochora.compiler.api.SourceInfo;

import java.util.Map;

/**
 * Generic, plugin-friendly directive. Namespaced for extensibility. Arguments
 * use a small typed value system to avoid Object-typed maps.
 *
 * @param namespace Directive namespace.
 * @param name Directive name.
 * @param args Map of argument names to values.
 * @param source Source info location.
 */
public record IrDirective(String namespace, String name, Map<String, IrValue> args, SourceInfo source) implements IrItem {}


