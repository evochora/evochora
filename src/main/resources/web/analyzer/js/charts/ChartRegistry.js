/**
 * Chart Registry
 * 
 * Central registry for chart types. Plugins define which chart type to use
 * via VisualizationHint.type, and the registry instantiates the appropriate renderer.
 * 
 * @module ChartRegistry
 */

const ChartRegistry = (function() {
    'use strict';
    
    // Registered chart types
    const types = {};
    
    /**
     * Registers a chart type.
     * 
     * @param {string} typeName - Chart type identifier (e.g. "line-chart")
     * @param {Object} chartModule - Chart module with render(container, data, config) method
     */
    function register(typeName, chartModule) {
        if (types[typeName]) {
            console.warn(`[ChartRegistry] Overwriting existing chart type: ${typeName}`);
        }
        types[typeName] = chartModule;
        console.debug(`[ChartRegistry] Registered chart type: ${typeName}`);
    }
    
    /**
     * Creates and renders a chart.
     * 
     * @param {string} typeName - Chart type identifier
     * @param {HTMLElement} container - Container element (canvas or div)
     * @param {Array<Object>} data - Data rows
     * @param {Object} config - Visualization config from VisualizationHint
     * @returns {Object} Chart instance (for later updates/destruction)
     */
    function render(typeName, container, data, config) {
        const chartModule = types[typeName];
        if (!chartModule) {
            throw new Error(`Unknown chart type: ${typeName}. Available: ${Object.keys(types).join(', ')}`);
        }
        return chartModule.render(container, data, config);
    }
    
    /**
     * Checks if a chart type is registered.
     * 
     * @param {string} typeName - Chart type identifier
     * @returns {boolean}
     */
    function has(typeName) {
        return !!types[typeName];
    }
    
    /**
     * Gets all registered chart type names.
     * 
     * @returns {string[]}
     */
    function getTypes() {
        return Object.keys(types);
    }
    
    // Public API
    return {
        register,
        render,
        has,
        getTypes
    };
    
})();

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = ChartRegistry;
}

