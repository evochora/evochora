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
        // Chart.js fills between one dataset and another. We define boundaries and fill between them.
        
        // Outer band: p0 to p100
        if (yKeys.length >= 7) {
            datasets.push(createBandDataset('Min/Max', PALETTE.outerBand, data, yKeys[0], yKeys[6]));
            datasets.push(createBandDataset('p10-p90', PALETTE.middleBand, data, yKeys[1], yKeys[5]));
            datasets.push(createBandDataset('IQR (p25-p75)', PALETTE.innerBand, data, yKeys[2], yKeys[4]));
        }
        
        // Median line
        if (yKeys.length >= 4) {
             datasets.push({
                label: 'Median Age',
                data: data.map(row => toNumber(row[yKeys[3]])),
                borderColor: PALETTE.medianLine,
                borderWidth: 2,
                pointRadius: 0,
                fill: false,
                tension: 0.2
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
                            filter: item => item.dataset.label !== '_boundary'
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
     * Helper to create a pair of datasets for a filled band.
     * We need two datasets: the lower bound and the upper bound.
     * The upper bound is filled down to the lower bound.
     */
    function createBandDataset(label, color, data, lowerKey, upperKey) {
        const lowerData = data.map(row => toNumber(row[lowerKey]));
        const upperData = data.map(row => toNumber(row[upperKey]));
        
        return {
            label: label,
            data: upperData,
            backgroundColor: color,
            borderColor: 'transparent',
            pointRadius: 0,
            fill: {
                target: {
                    value: lowerData.map((_, i) => lowerData[i]) // fill down to the lower bound data
                },
                above: color,
            },
            tension: 0.2
        };
    }
    
    function formatLabel(key) {
        return key.split('_').map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
    }
    
    function update(chart, data, config) {
        // More complex update logic would be needed for bands,
        // for simplicity we'll just re-render.
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
