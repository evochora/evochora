import * as ChartRegistry from './ChartRegistry.js';
import { formatTickValue, axisTicks, tooltipTitle, tooltipValue } from './ChartUtils.js';
import * as LowerBars from './LowerBars.js';

/**
 * Stacked Bar Chart Implementation
 *
 * Renders categorical data as stacked bar charts using Chart.js.
 * This is useful for showing part-to-whole relationships at discrete time points.
 *
 * Supports percentage mode where each bar totals 100%.
 * Supports optional secondary Y-axis (y2) for overlay line data.
 * Series may carry names ({@code labels}) and colors ({@code colors}) chosen by the metric, and a
 * metric may leave series that hold only zeros out of the legend ({@code hideEmpty}). Negative
 * values stack below zero, so one bar can show two opposite quantities.
 *
 * A metric may add a second group of bars below the chart ({@code lower}), fed from a companion
 * table and drawn on an axis of its own in absolute numbers; {@link LowerBars} builds that group,
 * its legend and its tooltip lines, and this chart only places it.
 *
 * @module StackedBarChart
 */
    
    // Evochora color palette
    const COLORS = [
        '#4a9eff', '#a0e0a0', '#ffb366', '#dda0dd', '#87ceeb', 
        '#ffd700', '#ff6b6b', '#98d8c8', '#f08080', '#c79ecf'
    ];
    
    /** Shares of the chart height the shares and the lower bars get when both are drawn. */
    const UPPER_WEIGHT = 7;
    const LOWER_WEIGHT = 3;

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
 * Tick options that leave a single "0" where two stacked axes meet: the upper axis writes its
 * zero without a unit, the lower one leaves its zero out.
 *
 * @param {Object} ticks - Tick options as axisTicks returns them
 * @param {string} zeroText - What this axis writes at zero
 * @returns {Object} Tick options with the zero replaced
 */
function withZeroAtSeam(ticks, zeroText) {
    const format = ticks.callback;
    return {
        ...ticks,
        callback: function(value, index, all) {
            if (value === 0) return zeroText;
            return format ? format.call(this, value, index, all) : value;
        }
    };
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
        
        // Count visible shares; the rate line and the lower bars are not shares
        let visibleCount = 0;
        chart.data.datasets.forEach((ds, idx) => {
            const dsMeta = chart.getDatasetMeta(idx);
            if (ds.yAxisID === 'y' && !dsMeta.hidden) {
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
     * @param {Object} [context] - Render context; its companion rows feed the lower bars
     * @returns {Chart} Chart.js instance
     */
export function render(canvas, data, config, context = {}) {
        const ctx = canvas.getContext('2d');

        const xKey = config.x || 'tick';
        const yKeys = Array.isArray(config.y) ? config.y : (config.y ? [config.y] : []);
        const isPercentage = config.yAxisMode === 'percent';
        // How the stacked quantity reads and what it is called, for bars that carry a quantity of
        // their own rather than shares of one
        const yFormat = config.yFormat || null;
        const yLabel = config.yLabel || null;
        const y2Key = config.y2 || null;
        const y2Label = config.y2Label || formatLabel(y2Key || '');
        const y2PeakTickKey = config.y2PeakTick || null;
        // Names and colors a metric chooses for its series; the rest are derived
        const seriesLabels = config.labels || {};
        const seriesColors = config.colors || {};
        const colorOf = (key, index) => seriesColors[key] || getColor(index);

        const labels = data.map(row => toNumber(row[xKey]));

        // Calculate percentages based on ALL categories (not just visible ones)
        const datasets = yKeys.map((key, index) => ({
            label: seriesLabels[key] || formatLabel(key),
            data: data.map(row => {
                const val = toNumber(row[key]);
                if (isPercentage) {
                    const sum = yKeys.reduce((acc, k) => acc + toNumber(row[k]), 0);
                    return sum === 0 ? 0 : (val / sum) * 100;
                }
                return val;
            }),
            borderColor: colorOf(key, index),
            backgroundColor: colorOf(key, index) + 'cc',
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

        // Bars below the chart, from a companion table
        const lower = LowerBars.build(data, xKey, context.companion || null, config.lower);
        const lowerDetails = new Map();
        (lower || []).forEach(segment => {
            const index = datasets.length;
            lowerDetails.set(index, segment.details);
            datasets.push({
                label: segment.label,
                data: segment.values,
                borderColor: segment.color,
                backgroundColor: segment.color + 'cc',
                borderWidth: 1,
                yAxisID: LowerBars.AXIS_ID,
                order: 1
            });
        });
        const hasLower = !!lower;

        // The pointer's height decides whether the tooltip speaks for the shares or for the
        // segment of the lower bars under it
        let pointerY = null;
        const pointerPlugin = {
            id: 'lowerBarsPointer',
            beforeEvent(chart, args) {
                pointerY = args.event?.y ?? null;
            }
        };
        const inLowerHalf = chart => {
            const scale = chart.scales[LowerBars.AXIS_ID];
            return hasLower && scale && pointerY != null && pointerY >= scale.top && pointerY <= scale.bottom;
        };

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
            plugins: [pointerPlugin, ...(y2LinePlugin ? [y2LinePlugin] : [])],
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
                            // A metric may leave out series that hold nothing in the shown window
                            filter: (item, legendData) => {
                                const dataset = legendData.datasets[item.datasetIndex];
                                // The lower bars have their own legend under the chart
                                if (dataset.yAxisID === LowerBars.AXIS_ID) return false;
                                return !config.hideEmpty || dataset.yAxisID !== 'y'
                                    || dataset.data.some(value => value !== 0);
                            },
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
                        filter: item => {
                            const isLower = item.dataset.yAxisID === LowerBars.AXIS_ID;
                            if (inLowerHalf(item.chart)) {
                                return isLower && LowerBars.isUnderPointer(item, pointerY);
                            }
                            if (isLower) return false;
                            return !config.hideEmpty || item.dataset.yAxisID !== 'y' || item.parsed.y !== 0;
                        },
                        callbacks: {
                            title: tooltipTitle,
                            label: context => {
                                if (context.dataset.yAxisID === LowerBars.AXIS_ID) {
                                    const details = lowerDetails.get(context.datasetIndex)?.[context.dataIndex];
                                    return LowerBars.tooltipLines(context.dataset.label, context.parsed.y, details);
                                }
                                let label = context.dataset.label || '';
                                if (label) {
                                    label += ': ';
                                }
                                if (context.parsed.y !== null) {
                                    // The second axis carries a rate whatever the bars show
                                    const percent = isPercentage || context.dataset.yAxisID === 'y2';
                                    label += tooltipValue(context.parsed.y, percent ? 'percent' : yFormat);
                                    // Where a rate peaked, spelled out rather than shortened
                                    if (context.dataset.yAxisID === 'y2' && context.dataset.peakTicks) {
                                        const peakTick = context.dataset.peakTicks[context.dataIndex];
                                        if (peakTick != null) {
                                            label += ` (at tick ${tooltipValue(Number(peakTick), 'integer')})`;
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
                    ...(hasLower ? {
                        [LowerBars.AXIS_ID]: {
                            type: 'linear',
                            position: 'left',
                            stack: 'leftPanels',
                            stackWeight: LOWER_WEIGHT,
                            stacked: true,
                            reverse: true,
                            min: 0,
                            title: {
                                display: true,
                                text: config.lower.label || '',
                                color: '#ff9b9b'
                            },
                            ticks: {
                                color: '#ff9b9b',
                                ...withZeroAtSeam(axisTicks('integer'), '')
                            },
                            grid: { color: '#2a1a1a', drawBorder: false }
                        }
                    } : {}),
                    y: {
                        stacked: true,
                        position: 'left',
                        ...(hasLower ? { stack: 'leftPanels', stackWeight: UPPER_WEIGHT } : {}),
                        // Start with max 100 (all categories visible)
                        max: isPercentage ? 100 : undefined,
                        title: yLabel
                            ? { display: true, text: yLabel, color: '#888' }
                            : { display: false },
                        ticks: {
                            color: '#888',
                            ...(hasLower
                                ? withZeroAtSeam(axisTicks(isPercentage ? 'percent' : yFormat), '0')
                                : axisTicks(isPercentage ? 'percent' : yFormat))
                        },
                        grid: { color: '#333', drawBorder: false }
                    },
                    ...(y2Key ? {
                        y2: {
                            type: 'linear',
                            position: 'right',
                            ...(hasLower ? { stack: 'rightPanels', stackWeight: UPPER_WEIGHT } : {}),
                            min: 0,
                            max: y2Max,
                            title: {
                                display: true,
                                text: y2Label,
                                color: '#888'
                            },
                            ticks: {
                                color: '#888',
                                ...axisTicks('percent')
                            },
                            grid: {
                                drawOnChartArea: false  // Don't draw grid lines over bars
                            }
                        }
                    } : {}),
                    ...(hasLower && y2Key ? {
                        // Keeps the rate axis as tall as the shares it belongs to. Stacked axes are
                        // placed in the order they are defined: on the right the first one is drawn
                        // on top, on the left at the bottom - hence the lower axis before y, and
                        // this spacer after y2
                        y2Spacer: {
                            type: 'linear',
                            position: 'right',
                            stack: 'rightPanels',
                            stackWeight: LOWER_WEIGHT,
                            ticks: { display: false },
                            grid: { display: false },
                            border: { display: false }
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
        chart._totalDatasets = yKeys.length;  // Only the shares count towards the 100% axis
        chart._hasY2 = !!y2Key;

        if (hasLower) {
            LowerBars.renderLegend(chart);
        } else {
            LowerBars.removeLegend(canvas);
        }

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
