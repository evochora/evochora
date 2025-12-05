import * as ChartRegistry from './ChartRegistry.js';

/**
 * Heatmap Chart Implementation
 * 
 * Renders 2D grid data as heatmaps.
 * Uses Canvas directly for performance with large grids.
 * 
 * @module HeatmapChart
 */

/**
 * Interpolates between two colors based on value.
 */
function interpolateColor(value, min, max) {
    const ratio = (value - min) / (max - min || 1);
    
    // Blue → Cyan → Green → Yellow → Red
    const colors = [
        [10, 30, 80],      // Dark blue
        [74, 158, 255],    // Accent blue
        [160, 224, 160],   // Accent green
        [255, 215, 0],     // Gold
        [255, 107, 107]    // Coral red
    ];
    
    const scaled = ratio * (colors.length - 1);
    const idx = Math.floor(scaled);
    const t = scaled - idx;
    
    if (idx >= colors.length - 1) {
        return colors[colors.length - 1];
    }
    
    const c1 = colors[idx];
    const c2 = colors[idx + 1];
    
    return [
        Math.round(c1[0] + (c2[0] - c1[0]) * t),
        Math.round(c1[1] + (c2[1] - c1[1]) * t),
        Math.round(c1[2] + (c2[2] - c1[2]) * t)
    ];
}

/**
 * Renders a heatmap.
 * 
 * @param {HTMLCanvasElement} canvas - Canvas element
 * @param {Array<Object>} data - Data rows with x, y, value fields
 * @param {Object} config - Visualization config with x, y, value fields
 * @returns {Object} Heatmap instance
 */
export function render(canvas, data, config) {
    const ctx = canvas.getContext('2d');
    
    const xKey = config.x || 'x';
    const yKey = config.y || 'y';
    const valueKey = config.value || 'value';
    
    // Find grid dimensions and value range
    let minX = Infinity, maxX = -Infinity;
    let minY = Infinity, maxY = -Infinity;
    let minVal = Infinity, maxVal = -Infinity;
    
    data.forEach(row => {
        const x = row[xKey];
        const y = row[yKey];
        const v = row[valueKey];
        
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
        minVal = Math.min(minVal, v);
        maxVal = Math.max(maxVal, v);
    });
    
    const gridWidth = maxX - minX + 1;
    const gridHeight = maxY - minY + 1;
    
    // Calculate cell size
    const cellWidth = canvas.width / gridWidth;
    const cellHeight = canvas.height / gridHeight;
    
    // Clear canvas
    ctx.fillStyle = '#0a0a14';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    
    // Draw cells
    data.forEach(row => {
        const x = row[xKey] - minX;
        const y = row[yKey] - minY;
        const v = row[valueKey];
        
        const [r, g, b] = interpolateColor(v, minVal, maxVal);
        ctx.fillStyle = `rgb(${r},${g},${b})`;
        ctx.fillRect(x * cellWidth, y * cellHeight, cellWidth, cellHeight);
    });
    
    // Return instance object
    return {
        canvas,
        config,
        data,
        
        // For compatibility with Chart.js API
        destroy: function() {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
        }
    };
}

export function update(instance, data, config) {
    return render(instance.canvas, data, config);
}

export function destroy(instance) {
    if (instance && instance.destroy) {
        instance.destroy();
    }
}

// Register with ChartRegistry
ChartRegistry.register('heatmap', { render, update, destroy });
