/**
 * Helpers that belong to no part of Evochora: what any Java program could use the same way, such
 * as walking the causes of an exception. Every top-level package but {@code runtime} may use
 * them, and they use none of Evochora's own, as {@code PackageDependencyRulesTest} enforces. A
 * helper that knows about runs, organisms, the pipeline or the node belongs to the package of that
 * part instead, even when several parts use it.
 */
package org.evochora.util;
