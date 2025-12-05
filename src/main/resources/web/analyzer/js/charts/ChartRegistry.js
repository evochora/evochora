/**
 * Chart Registry
 * 
 * Central registry for chart types. Plugins define which chart type to use
 * via VisualizationHint.type, and the registry instantiates the appropriate renderer.
 * 
 * @module ChartRegistry
 */

// Registered chart types
const types = {};

/**
 * Registers a chart class constructor.
 * 
 * @param {string} typeName - Chart type identifier (e.g. "line-chart")
 * @param {Function} chartClass - The chart class constructor.
 */
export function register(typeName, chartClass) {
    if (types[typeName]) {
        console.warn(`[ChartRegistry] Overwriting existing chart type: ${typeName}`);
    }
    types[typeName] = chartClass;
    console.debug(`[ChartRegistry] Registered chart type: ${typeName}`);
}

/**
 * Retrieves a chart class constructor by its type name.
 * 
 * @param {string} typeName - Chart type identifier
 * @returns {Function|null} The chart class constructor, or null if not found.
 */
export function getChart(typeName) {
    const chartClass = types[typeName];
    if (!chartClass) {
        console.warn(`[ChartRegistry] Unknown chart type: ${typeName}. Available: ${Object.keys(types).join(', ')}`);
        return null;
    }
    return chartClass;
}

/**
 * Checks if a chart type is registered.
 * 
 * @param {string} typeName - Chart type identifier
 * @returns {boolean}
 */
export function has(typeName) {
    return !!types[typeName];
}

/**
 * Gets all registered chart type names.
 * 
 * @returns {string[]}
 */
export function getTypes() {
    return Object.keys(types);
}

