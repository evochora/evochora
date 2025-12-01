/**
 * Bar Chart Implementation
 * 
 * Renders categorical/histogram data as bar charts using Chart.js.
 * 
 * @module BarChart
 */

const BarChart = (function() {
    'use strict';
    
    // Evochora color palette
    const COLORS = [
        '#4a9eff', // accent blue
        '#a0e0a0', // accent green
        '#ffb366', // accent orange
        '#dda0dd', // plum
        '#87ceeb', // sky blue
    ];
    
    /**
     * Renders a bar chart.
     * 
     * @param {HTMLCanvasElement} canvas - Canvas element
     * @param {Array<Object>} data - Data rows
     * @param {Object} config - Visualization config with x, y fields
     * @returns {Chart} Chart.js instance
     */
    function render(canvas, data, config) {
        const ctx = canvas.getContext('2d');
        
        const xKey = config.x || 'category';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : ['value']);
        
        // Prepare labels and datasets
        const labels = data.map(row => row[xKey]);
        const datasets = yKeys.map((key, idx) => ({
            label: formatLabel(key),
            data: data.map(row => row[key]),
            backgroundColor: COLORS[idx % COLORS.length] + 'cc',
            borderColor: COLORS[idx % COLORS.length],
            borderWidth: 1
        }));
        
        const chartConfig = {
            type: 'bar',
            data: {
                labels: labels,
                datasets: datasets
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: {
                        display: yKeys.length > 1,
                        position: 'top',
                        labels: {
                            color: '#e0e0e0',
                            font: {
                                family: "'Courier New', monospace",
                                size: 11
                            }
                        }
                    },
                    tooltip: {
                        backgroundColor: '#191923',
                        titleColor: '#e0e0e0',
                        bodyColor: '#aaa',
                        borderColor: '#333',
                        borderWidth: 1
                    }
                },
                scales: {
                    x: {
                        ticks: { color: '#888' },
                        grid: { color: '#333' }
                    },
                    y: {
                        ticks: { color: '#888' },
                        grid: { color: '#333' }
                    }
                }
            }
        };
        
        return new Chart(ctx, chartConfig);
    }
    
    function formatLabel(key) {
        return key.split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
    }
    
    function update(chart, data, config) {
        const xKey = config.x || 'category';
        const yKeys = Array.isArray(config.y) ? config.y : [config.y || 'value'];
        
        chart.data.labels = data.map(row => row[xKey]);
        yKeys.forEach((key, idx) => {
            if (chart.data.datasets[idx]) {
                chart.data.datasets[idx].data = data.map(row => row[key]);
            }
        });
        chart.update('none');
    }
    
    function destroy(chart) {
        if (chart) chart.destroy();
    }
    
    return { render, update, destroy };
    
})();

// Register with ChartRegistry
if (typeof ChartRegistry !== 'undefined') {
    ChartRegistry.register('bar-chart', BarChart);
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = BarChart;
}

