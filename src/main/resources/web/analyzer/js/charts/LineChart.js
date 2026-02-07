import * as ChartRegistry from './ChartRegistry.js';
import { formatTickValue } from './ChartUtils.js';

/**
 * Line Chart Implementation
 * 
 * Renders time-series data as line charts using Chart.js.
 * Supports dual Y-axes and automatic color assignment.
 * 
 * @module LineChart
 */
    
    // Evochora color palette
    const COLORS = [
        '#4a9eff', // accent blue
        '#a0e0a0', // accent green
        '#ffb366', // accent orange
        '#dda0dd', // plum
        '#87ceeb', // sky blue
        '#ffd700', // gold
        '#ff6b6b', // coral
        '#98d8c8', // mint
    ];
    
    /**
     * Gets color for a series by index.
     */
    function getColor(index) {
        return COLORS[index % COLORS.length];
    }
    
    /**
     * Converts BigInt values to Numbers for Chart.js compatibility.
     * DuckDB returns BIGINT columns as JavaScript BigInt, but Chart.js requires Number.
     */
    function toNumber(value) {
        if (typeof value === 'bigint') {
            return Number(value);
        }
        return value;
    }

/**
 * Formats a column key as a human-readable label.
 * Converts snake_case to Title Case.
 */
function formatLabel(key) {
    return key
        .split('_')
        .map(word => word.charAt(0).toUpperCase() + word.slice(1))
        .join(' ');
}

/**
 * Checks if all values in an array are integers.
 * @param {Array<number>} values - Array of numbers
 * @returns {boolean} True if all values are integers
 */
function allIntegers(values) {
    return values.every(v => v == null || Number.isInteger(v));
}
    
    /**
     * Renders a line chart.
     * 
     * @param {HTMLCanvasElement} canvas - Canvas element
     * @param {Array<Object>} data - Data rows (array of objects)
     * @param {Object} config - Visualization config with x, y, y2 fields
     * @returns {Chart} Chart.js instance
     */
export function render(canvas, data, config) {
        const ctx = canvas.getContext('2d');
        
        // Extract config
        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        const y2Keys = Array.isArray(config.y2) ? config.y2 : (config.y2 ? [config.y2] : []);
        
        // Prepare labels (x-axis values) - convert BigInt to Number
        const labels = data.map(row => toNumber(row[xKey]));
        
        // Prepare datasets
        const datasets = [];
        let colorIndex = 0;

        // Collect values for integer detection
        const yValues = [];
        const y2Values = [];

        // Primary Y-axis datasets - convert BigInt to Number
        yKeys.forEach(key => {
            const values = data.map(row => toNumber(row[key]));
            yValues.push(...values);
            datasets.push({
                label: formatLabel(key),
                data: values,
                borderColor: getColor(colorIndex),
                backgroundColor: getColor(colorIndex) + '20',
                borderWidth: 2,
                fill: false,
                tension: 0.1,
                pointRadius: data.length > 100 ? 0 : 3,
                pointHoverRadius: 5,
                yAxisID: 'y'
            });
            colorIndex++;
        });

        // Secondary Y-axis datasets - convert BigInt to Number
        y2Keys.forEach(key => {
            const values = data.map(row => toNumber(row[key]));
            y2Values.push(...values);
            datasets.push({
                label: formatLabel(key),
                data: values,
                borderColor: getColor(colorIndex),
                backgroundColor: getColor(colorIndex) + '20',
                borderWidth: 2,
                borderDash: [5, 5], // Dashed line for secondary axis
                fill: false,
                tension: 0.1,
                pointRadius: data.length > 100 ? 0 : 3,
                pointHoverRadius: 5,
                yAxisID: 'y2'
            });
            colorIndex++;
        });

        // Detect if axes should use integer formatting
        const yIsInteger = allIntegers(yValues);
        const y2IsInteger = allIntegers(y2Values);
        
        // Chart configuration
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
                            font: {
                                family: "'Courier New', monospace",
                                size: 11
                            },
                            usePointStyle: true,
                            pointStyle: 'line'
                        }
                    },
                    tooltip: {
                        backgroundColor: '#191923',
                        titleColor: '#e0e0e0',
                        bodyColor: '#aaa',
                        borderColor: '#333',
                        borderWidth: 1,
                        padding: 12,
                        displayColors: true,
                        callbacks: {
                            title: function(items) {
                                return `Tick ${items[0].label}`;
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
                        grid: {
                            color: '#333',
                            drawBorder: false
                        }
                    },
                    y: {
                        type: 'linear',
                        display: true,
                        position: 'left',
                        title: {
                            display: yKeys.length > 0,
                            text: yKeys.map(formatLabel).join(', '),
                            color: '#888'
                        },
                        ticks: {
                            color: '#888',
                            callback: function(value) {
                                if (yIsInteger) {
                                    // Integer mode: only show whole numbers
                                    return Number.isInteger(value) ? value : null;
                                }
                                // Float mode: show with appropriate precision
                                return value.toFixed(2);
                            }
                        },
                        grid: {
                            color: '#333',
                            drawBorder: false
                        }
                    },
                    y2: {
                        type: 'linear',
                        display: y2Keys.length > 0,
                        position: 'right',
                        title: {
                            display: y2Keys.length > 0,
                            text: y2Keys.map(formatLabel).join(', '),
                            color: '#888'
                        },
                        ticks: {
                            color: '#888',
                            callback: function(value) {
                                if (y2IsInteger) {
                                    // Integer mode: only show whole numbers
                                    return Number.isInteger(value) ? value : null;
                                }
                                // Float mode: show with appropriate precision
                                return value.toFixed(2);
                            }
                        },
                        grid: {
                            drawOnChartArea: false
                        }
                    }
                },
                animation: {
                    duration: 500
                }
            }
        };
        
        return new Chart(ctx, chartConfig);
    }
    
    /**
     * Updates an existing chart with new data.
     * 
     * @param {Chart} chart - Existing Chart.js instance
     * @param {Array<Object>} data - New data rows
     * @param {Object} config - Visualization config
     */
export function update(chart, data, config) {
        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        const y2Keys = Array.isArray(config.y2) ? config.y2 : (config.y2 ? [config.y2] : []);
        
        // Update labels - convert BigInt to Number
        chart.data.labels = data.map(row => toNumber(row[xKey]));
        
        // Update datasets - convert BigInt to Number
        let datasetIndex = 0;
        
        yKeys.forEach(key => {
            if (chart.data.datasets[datasetIndex]) {
                chart.data.datasets[datasetIndex].data = data.map(row => toNumber(row[key]));
            }
            datasetIndex++;
        });
        
        y2Keys.forEach(key => {
            if (chart.data.datasets[datasetIndex]) {
                chart.data.datasets[datasetIndex].data = data.map(row => toNumber(row[key]));
            }
            datasetIndex++;
        });
        
        // Adjust point radius based on data size
        const pointRadius = data.length > 100 ? 0 : 3;
        chart.data.datasets.forEach(ds => {
            ds.pointRadius = pointRadius;
        });
        
        chart.update('none'); // No animation for updates
    }
    
    /**
     * Destroys a chart instance.
     * 
     * @param {Chart} chart - Chart.js instance
     */
export function destroy(chart) {
        if (chart) {
            chart.destroy();
        }
    }

// Register with ChartRegistry
ChartRegistry.register('line-chart', { render, update, destroy });
