import * as ChartRegistry from './ChartRegistry.js';
import { formatTickValue } from './ChartUtils.js';

/**
 * Stacked Area Chart Implementation
 * 
 * Renders time-series data as stacked area charts using Chart.js.
 * This is useful for showing how part-to-whole relationships change over time.
 * 
 * @module StackedAreaChart
 */
    
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

function formatLabel(key) {
    return key.split('_').map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}

/**
 * Creates a legend click handler that adjusts Y-axis max.
 * When all categories visible: max = 100
 * When some hidden: max = undefined (auto-scale)
 */
function createLegendClickHandler() {
    return function(e, legendItem, legend) {
        const chart = legend.chart;
        const index = legendItem.datasetIndex;
        
        // Toggle visibility (default behavior)
        const meta = chart.getDatasetMeta(index);
        meta.hidden = meta.hidden === null ? !chart.data.datasets[index].hidden : null;
        
        // Count visible datasets
        let visibleCount = 0;
        chart.data.datasets.forEach((ds, idx) => {
            const dsMeta = chart.getDatasetMeta(idx);
            if (!dsMeta.hidden) {
                visibleCount++;
            }
        });
        
        // Adjust Y-axis max
        if (visibleCount === chart._totalDatasets) {
            // All visible: fix at 100%
            chart.options.scales.y.max = 100;
        } else {
            // Some hidden: auto-scale
            chart.options.scales.y.max = undefined;
        }
        
        chart.update();
    };
}
    
    /**
     * Renders a stacked area chart.
     * 
     * @param {HTMLCanvasElement} canvas - Canvas element
     * @param {Array<Object>} data - Data rows (array of objects)
     * @param {Object} config - Visualization config with x and y fields
     * @returns {Chart} Chart.js instance
     */
export function render(canvas, data, config) {
        const ctx = canvas.getContext('2d');
        
        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        const isPercentage = config.yAxisMode === 'percent';
        
        const labels = data.map(row => toNumber(row[xKey]));
        
        // Calculate percentages based on ALL categories (not just visible ones)
        const datasets = yKeys.map((key, index) => ({
            label: formatLabel(key),
            data: data.map(row => {
                const val = toNumber(row[key]);
                if (isPercentage) {
                    const sum = yKeys.reduce((acc, k) => acc + toNumber(row[k]), 0);
                    return sum === 0 ? 0 : (val / sum) * 100;
                }
                return val;
            }),
            borderColor: getColor(index),
            backgroundColor: getColor(index) + '80',
            borderWidth: 1,
            fill: true,
            tension: 0.2,
            pointRadius: 0,
            pointHoverRadius: 5
        }));
        
        const chartConfig = {
            type: 'line',
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
                        },
                        // Custom click handler to adjust Y-axis max
                        onClick: isPercentage ? createLegendClickHandler() : undefined
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
                            label: context => {
                                let label = context.dataset.label || '';
                                if (label) {
                                    label += ': ';
                                }
                                if (context.parsed.y !== null) {
                                    label += context.parsed.y.toFixed(2);
                                    if (isPercentage) label += '%';
                                }
                                return label;
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        title: { display: false },
                        ticks: {
                            color: '#888',
                            maxTicksLimit: 10,
                            callback: function(value) {
                                const label = this.getLabelForValue(value);
                                return formatTickValue(label);
                            }
                        },
                        grid: { color: '#333', drawBorder: false }
                    },
                    y: {
                        stacked: true,
                        // Start with max 100 (all categories visible)
                        max: isPercentage ? 100 : undefined,
                        title: { display: false },
                        ticks: { 
                            color: '#888',
                            callback: function(value) {
                                // Use decimals when Y-axis max is small (< 10)
                                const max = this.max || 100;
                                const decimals = max < 10 ? 1 : 0;
                                return value.toFixed(decimals) + (isPercentage ? '%' : '');
                            }
                        },
                        grid: { color: '#333', drawBorder: false }
                    }
                },
                animation: {
                    duration: 500
                }
            }
        };
        
        const chart = new Chart(ctx, chartConfig);
        
        // Store metadata
        chart._isPercentage = isPercentage;
        chart._totalDatasets = datasets.length;
        
        return chart;
    }
    
export function update(chart, data, config) {
        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        const isPercentage = config.yAxisMode === 'percent';
        
        chart.data.labels = data.map(row => toNumber(row[xKey]));
        
        yKeys.forEach((key, index) => {
            if (chart.data.datasets[index]) {
                chart.data.datasets[index].data = data.map(row => {
                    const val = toNumber(row[key]);
                    if (isPercentage) {
                        const sum = yKeys.reduce((acc, k) => acc + toNumber(row[k]), 0);
                        return sum === 0 ? 0 : (val / sum) * 100;
                    }
                    return val;
                });
            }
        });
        
        chart.update('none');
    }
    
export function destroy(chart) {
        if (chart) {
            chart.destroy();
        }
    }

// Register with ChartRegistry
ChartRegistry.register('stacked-area-chart', { render, update, destroy });
