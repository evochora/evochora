/**
 * Band Chart Implementation
 * 
 * Renders percentile data as layered bands to show distribution over time.
 * This is ideal for visualizing age distributions, showing min/max, interquartile range, etc.
 * 
 * @module BandChart
 */

const BandChart = (function() {
    'use strict';
    
    // Custom palette for bands (darker to lighter) and median
    const PALETTE = {
        outerBand: '#4a9eff20',   // p0-p100 (min/max)
        middleBand: '#4a9eff40',  // p10-p90
        innerBand: '#4a9eff60',   // p25-p75 (IQR)
        medianLine: '#a0e0a0',    // p50 (median)
    };
    
    function toNumber(value) {
        if (typeof value === 'bigint') {
            return Number(value);
        }
        return value;
    }
    
    /**
     * Renders a band chart.
     * 
     * @param {HTMLCanvasElement} canvas - Canvas element
     * @param {Array<Object>} data - Data rows
     * @param {Object} config - Visualization config
     * @returns {Chart} Chart.js instance
     */
    function render(canvas, data, config) {
        const ctx = canvas.getContext('2d');
        
        const xKey = config.x || 'tick';
        // yKeys should be in order: [p0, p10, p25, p50, p75, p90, p100]
        const yKeys = config.y || []; 
        
        const labels = data.map(row => toNumber(row[xKey]));
        
        const datasets = [];
        
        // --- Create datasets for bands ---
        // Each band needs TWO datasets: lower boundary + upper boundary with fill
        
        if (yKeys.length >= 7) {
            // Outer band: p0 to p100 (min/max)
            addBandDatasets(datasets, data, yKeys[0], yKeys[6], 'Min/Max', PALETTE.outerBand);
            // Middle band: p10 to p90
            addBandDatasets(datasets, data, yKeys[1], yKeys[5], 'p10-p90', PALETTE.middleBand);
            // Inner band: p25 to p75 (IQR)
            addBandDatasets(datasets, data, yKeys[2], yKeys[4], 'IQR (p25-p75)', PALETTE.innerBand);
        }
        
        // Median line (on top)
        if (yKeys.length >= 4) {
             datasets.push({
                label: 'Median Age',
                data: data.map(row => toNumber(row[yKeys[3]])),
                borderColor: PALETTE.medianLine,
                borderWidth: 2,
                pointRadius: 0,
                fill: false,
                tension: 0.4 // Smooth curves
            });
        }
       
        const chartConfig = {
            type: 'line',
            data: {
                labels: labels,
                datasets: datasets
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'index', intersect: false },
                plugins: {
                    legend: {
                        position: 'top',
                        labels: {
                            color: '#e0e0e0',
                            font: { family: "'Courier New', monospace", size: 11 },
                            usePointStyle: true,
                            // Filter out boundary datasets from legend
                            filter: item => !item.text.startsWith('_')
                        }
                    },
                    tooltip: {
                        backgroundColor: '#191923',
                        titleColor: '#e0e0e0',
                        bodyColor: '#aaa',
                        borderColor: '#333',
                        borderWidth: 1,
                        padding: 12,
                        callbacks: {
                            title: items => `Tick ${items[0].label}`,
                            // Hide tooltips for boundary lines
                            filter: item => !item.dataset.label.startsWith('_')
                        }
                    }
                },
                scales: {
                    x: {
                        title: { display: true, text: formatLabel(xKey), color: '#888' },
                        ticks: { color: '#888', maxTicksLimit: 10 },
                        grid: { color: '#333', drawBorder: false }
                    },
                    y: {
                        title: { display: true, text: 'Age', color: '#888' },
                        ticks: { color: '#888' },
                        grid: { color: '#333', drawBorder: false }
                    }
                }
            }
        };
        
        return new Chart(ctx, chartConfig);
    }
    
    /**
     * Adds two datasets for a filled band: lower boundary + upper boundary.
     * The upper boundary fills down to the lower boundary.
     */
    function addBandDatasets(datasets, data, lowerKey, upperKey, label, color) {
        // Lower boundary (invisible, just for fill target)
        datasets.push({
            label: '_' + label + '_lower',
            data: data.map(row => toNumber(row[lowerKey])),
            borderColor: 'transparent',
            backgroundColor: 'transparent',
            pointRadius: 0,
            fill: false,
            tension: 0.4 // Smooth curves
        });
        
        // Upper boundary (fills down to previous dataset = lower boundary)
        datasets.push({
            label: label,
            data: data.map(row => toNumber(row[upperKey])),
            borderColor: 'transparent',
            backgroundColor: color,
            pointRadius: 0,
            fill: '-1', // Fill to the previous dataset (the lower boundary)
            tension: 0.4 // Smooth curves
        });
    }
    
    function formatLabel(key) {
        return key.split('_').map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
    }
    
    function update(chart, data, config) {
        // For bands, just re-render (simpler than updating all datasets)
        if (chart) chart.destroy();
        const canvas = chart.canvas;
        return render(canvas, data, config);
    }
    
    function destroy(chart) {
        if (chart) {
            chart.destroy();
        }
    }
    
    return { render, update, destroy };
    
})();

// Register with ChartRegistry
if (typeof ChartRegistry !== 'undefined') {
    ChartRegistry.register('band-chart', BandChart);
}

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = BandChart;
}
