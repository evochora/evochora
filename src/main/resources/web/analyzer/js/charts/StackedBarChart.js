import * as ChartRegistry from './ChartRegistry.js';
import { formatTickValue } from './ChartUtils.js';

/**
 * Stacked Bar Chart Implementation
 *
 * Renders categorical data as stacked bar charts using Chart.js.
 * This is useful for showing part-to-whole relationships at discrete time points.
 *
 * Supports percentage mode where each bar totals 100%.
 * Supports optional secondary Y-axis (y2) for overlay line data.
 *
 * @module StackedBarChart
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
 * Calculates an appropriate max value for the secondary Y-axis.
 * Adds ~20% headroom and rounds to a nice number for readability.
 */
function calculateY2Max(data, y2Key) {
    const maxValue = Math.max(...data.map(row => toNumber(row[y2Key]) || 0));
    if (maxValue === 0) return 1;  // Small scale when no failures

    // Add 20% headroom
    const withHeadroom = maxValue * 1.2;

    // Round to nice numbers - includes small values for low failure rates
    const niceNumbers = [0.1, 0.2, 0.5, 1, 2, 5, 10, 20, 50, 100];
    for (const nice of niceNumbers) {
        if (withHeadroom <= nice) return nice;
    }
    return 100;
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
     * Renders a stacked bar chart.
     *
     * @param {HTMLCanvasElement} canvas - Canvas element
     * @param {Array<Object>} data - Data rows (array of objects)
     * @param {Object} config - Visualization config with x, y, and optional y2 fields
     * @returns {Chart} Chart.js instance
     */
export function render(canvas, data, config) {
        const ctx = canvas.getContext('2d');

        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        const isPercentage = config.yAxisMode === 'percent';
        const y2Key = config.y2 || null;
        const y2Label = config.y2Label || formatLabel(y2Key || '');
        const y2PeakTickKey = config.y2PeakTick || null;

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
            backgroundColor: getColor(index) + 'cc',
            borderWidth: 1,
            yAxisID: 'y',
            order: 1  // Draw bars first (lower order = drawn earlier = behind)
        }));

        // Store y2 data for manual drawing (Chart.js order doesn't work well with stacked bars)
        const y2Data = y2Key ? data.map(row => toNumber(row[y2Key])) : null;

        // Add y2 dataset for legend and tooltip only (invisible, will be drawn manually)
        if (y2Key) {
            datasets.push({
                label: y2Label,
                data: y2Data,
                type: 'line',
                borderColor: '#cc0000',
                backgroundColor: 'transparent',
                borderWidth: 0,  // Invisible - drawn manually by plugin
                pointRadius: 0,
                pointHoverRadius: 6,
                tension: 0.3,
                yAxisID: 'y2',
                // Store peak tick data for tooltip (no k/M formatting)
                peakTicks: y2PeakTickKey ? data.map(row => toNumber(row[y2PeakTickKey])) : null
            });
        }

        // Calculate appropriate max for y2 axis (auto-scale with headroom)
        const y2Max = y2Key ? calculateY2Max(data, y2Key) : undefined;

        // Plugin to draw y2 line on top of everything
        const y2LinePlugin = y2Key ? {
            id: 'y2LineOverlay',
            afterDatasetsDraw(chart) {
                const y2Dataset = chart.data.datasets.find(ds => ds.yAxisID === 'y2');
                if (!y2Dataset || !y2Dataset.data || y2Dataset.data.length === 0) return;

                const ctx = chart.ctx;
                const xScale = chart.scales.x;
                const yScale = chart.scales.y2;
                if (!yScale) return;

                const points = y2Dataset.data.map((value, index) => ({
                    x: xScale.getPixelForValue(index),
                    y: yScale.getPixelForValue(value)
                }));

                // Draw black outline
                ctx.save();
                ctx.beginPath();
                ctx.strokeStyle = '#000000';
                ctx.lineWidth = 5;
                ctx.lineJoin = 'round';
                ctx.lineCap = 'round';
                points.forEach((point, i) => {
                    if (i === 0) ctx.moveTo(point.x, point.y);
                    else ctx.lineTo(point.x, point.y);
                });
                ctx.stroke();

                // Draw red line on top
                ctx.beginPath();
                ctx.strokeStyle = '#cc0000';
                ctx.lineWidth = 2;
                points.forEach((point, i) => {
                    if (i === 0) ctx.moveTo(point.x, point.y);
                    else ctx.lineTo(point.x, point.y);
                });
                ctx.stroke();
                ctx.restore();
            }
        } : null;

        const chartConfig = {
            type: 'bar',
            data: {
                labels: labels,
                datasets: datasets
            },
            plugins: y2LinePlugin ? [y2LinePlugin] : [],
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
                            pointStyle: 'rect',
                            // Custom label generation to show line icon for y2 dataset
                            generateLabels: function(chart) {
                                const labels = Chart.defaults.plugins.legend.labels.generateLabels(chart);
                                return labels.map(label => {
                                    const dataset = chart.data.datasets[label.datasetIndex];
                                    if (dataset.yAxisID === 'y2') {
                                        // Override for y2: show line style with visible color
                                        label.pointStyle = 'line';
                                        label.strokeStyle = '#cc0000';
                                        label.fillStyle = '#cc0000';
                                        label.lineWidth = 3;
                                    }
                                    return label;
                                });
                            }
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
                                    label += context.parsed.y.toFixed(1);
                                    // Add % for percentage mode or y2 axis (failure rate)
                                    if (isPercentage || context.dataset.yAxisID === 'y2') {
                                        label += '%';
                                    }
                                    // Add peak tick for y2 (exact number, no k/M)
                                    if (context.dataset.yAxisID === 'y2' && context.dataset.peakTicks) {
                                        const peakTick = context.dataset.peakTicks[context.dataIndex];
                                        if (peakTick != null) {
                                            label += ` (at tick ${peakTick})`;
                                        }
                                    }
                                }
                                return label;
                            }
                        }
                    }
                },
                scales: {
                    x: {
                        stacked: true,
                        title: { display: false },
                        ticks: {
                            color: '#888',
                            maxTicksLimit: 15,
                            callback: function(value, index) {
                                // For bar charts, value is index - get actual label
                                const label = this.getLabelForValue(value);
                                const firstLabel = this.getLabelForValue(this.min);
                                const lastLabel = this.getLabelForValue(this.max);
                                const range = Number(lastLabel) - Number(firstLabel);
                                return formatTickValue(label, range);
                            }
                        },
                        grid: { color: '#333', drawBorder: false }
                    },
                    y: {
                        stacked: true,
                        position: 'left',
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
                    },
                    ...(y2Key ? {
                        y2: {
                            type: 'linear',
                            position: 'right',
                            min: 0,
                            max: y2Max,
                            title: {
                                display: true,
                                text: y2Label,
                                color: '#888'
                            },
                            ticks: {
                                color: '#888',
                                callback: function(value) {
                                    // Show decimals for small scales
                                    const decimals = this.max < 1 ? 2 : (this.max < 10 ? 1 : 0);
                                    return value.toFixed(decimals) + '%';
                                }
                            },
                            grid: {
                                drawOnChartArea: false  // Don't draw grid lines over bars
                            }
                        }
                    } : {})
                },
                animation: {
                    duration: 500
                }
            }
        };
        
        const chart = new Chart(ctx, chartConfig);
        
        // Store metadata
        chart._isPercentage = isPercentage;
        chart._totalDatasets = y2Key ? datasets.length - 1 : datasets.length;  // Exclude y2 from count
        chart._hasY2 = !!y2Key;

        return chart;
    }
    
export function update(chart, data, config) {
        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        const isPercentage = config.yAxisMode === 'percent';
        const y2Key = config.y2 || null;
        const y2PeakTickKey = config.y2PeakTick || null;

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

        // Update secondary Y-axis data if present
        if (y2Key && chart._hasY2) {
            const y2DatasetIndex = yKeys.length;
            if (chart.data.datasets[y2DatasetIndex]) {
                chart.data.datasets[y2DatasetIndex].data = data.map(row => toNumber(row[y2Key]));
                // Update peak tick data
                if (y2PeakTickKey) {
                    chart.data.datasets[y2DatasetIndex].peakTicks = data.map(row => toNumber(row[y2PeakTickKey]));
                }
            }
        }

        chart.update('none');
    }
    
export function destroy(chart) {
        if (chart) {
            chart.destroy();
        }
    }

// Register with ChartRegistry
ChartRegistry.register('stacked-bar-chart', { render, update, destroy });
