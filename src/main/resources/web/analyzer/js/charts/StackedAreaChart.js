/**
 * Stacked Area Chart Implementation
 * 
 * Renders time-series data as stacked area charts using Chart.js.
 * This is useful for showing how part-to-whole relationships change over time.
 * 
 * @module StackedAreaChart
 */

const StackedAreaChart = (function() {
    'use strict';
    
    // Evochora color palette
    const COLORS = [
        '#4a9eff', '#a0e0a0', '#ffb366', '#dda0dd', '#87ceeb', 
        '#ffd700', '#ff6b6b', '#98d8c8', '#f08080', '#c79ecf'
    ];
    
    function getColor(index) {
        return COLORS[index % COLORS.length];
    }
    
    function toNumber(value) {
        if (typeof value === 'bigint') {
            return Number(value);
        }
        return value;
    }
    
    /**
     * Renders a stacked area chart.
     * 
     * @param {HTMLCanvasElement} canvas - Canvas element
     * @param {Array<Object>} data - Data rows (array of objects)
     * @param {Object} config - Visualization config with x and y fields
     * @returns {Chart} Chart.js instance
     */
    function render(canvas, data, config) {
        const ctx = canvas.getContext('2d');
        
        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        
        const labels = data.map(row => toNumber(row[xKey]));
        
        const datasets = yKeys.map((key, index) => ({
            label: formatLabel(key),
            data: data.map(row => toNumber(row[key])),
            borderColor: getColor(index),
            backgroundColor: getColor(index) + '80', // More opaque for area
            borderWidth: 1,
            fill: true, // Key change for area chart
            tension: 0.2,
            pointRadius: 0,
            pointHoverRadius: 5
        }));
        
        const chartConfig = {
            type: 'line', // Still 'line' type in Chart.js for area charts
            data: {
                labels: labels,
                datasets: datasets
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: {
                    mode: 'index',
                    intersect: false
                },
                plugins: {
                    legend: {
                        position: 'top',
                        labels: {
                            color: '#e0e0e0',
                            font: { family: "'Courier New', monospace", size: 11 },
                            usePointStyle: true,
                            pointStyle: 'rect'
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
                            title: items => `Tick ${items[0].label}`
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
                        stacked: true, // Key change for stacking
                        title: { display: false },
                        ticks: { color: '#888' },
                        grid: { color: '#333', drawBorder: false }
                    }
                },
                animation: {
                    duration: 500
                }
            }
        };
        
        return new Chart(ctx, chartConfig);
    }
    
    function formatLabel(key) {
        return key.split('_').map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
    }
    
    function update(chart, data, config) {
        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        
        chart.data.labels = data.map(row => toNumber(row[xKey]));
        
        yKeys.forEach((key, index) => {
            if (chart.data.datasets[index]) {
                chart.data.datasets[index].data = data.map(row => toNumber(row[key]));
            }
        });
        
        chart.update('none');
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
    ChartRegistry.register('stacked-area-chart', StackedAreaChart);
}

// Export for module systems
if (typeof module !== 'undefined' && module.exports) {
    module.exports = StackedAreaChart;
}
