import * as ChartRegistry from './ChartRegistry.js';
import { formatTickValue, axisTicks, tooltipTitle, tooltipValue } from './ChartUtils.js';

/**
 * Band Chart Implementation
 * 
 * Renders percentile data as layered bands to show distribution over time.
 * This is ideal for visualizing age distributions, showing min/max, interquartile range, etc.
 * 
 * @module BandChart
 */
    
    // Bands get more opaque towards the middle, so the innermost is the most present
    const BAND_ALPHA = ['20', '40', '60', '80'];
    const BAND_BASE = '#4a9eff';

    const PALETTE = {
        medianLine: '#a0e0a0',
    };

    const SECOND_AXIS_COLORS = ['#ffb366', '#dda0dd'];

    /**
     * Colour of the nth band counted from the outside.
     *
     * @param {number} index - 0 for the outermost band
     * @param {number} total - How many bands there are
     * @returns {string} An rgba-style hex colour
     */
    function bandColor(index, total) {
        const step = Math.max(0, BAND_ALPHA.length - total);
        return BAND_BASE + BAND_ALPHA[Math.min(BAND_ALPHA.length - 1, index + step)];
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
    
    /**
     * Renders a band chart.
     * 
     * @param {HTMLCanvasElement} canvas - Canvas element
     * @param {Array<Object>} data - Data rows
     * @param {Object} config - Visualization config
     * @returns {Chart} Chart.js instance
     */
export function render(canvas, data, config) {
        const ctx = canvas.getContext('2d');
        
        const xKey = config.x || 'tick';
        // Percentiles in ascending order, an odd number of them: outermost pair first, the
        // middle one last. Three, five and seven all work; the outermost pair becomes the
        // faintest band, each pair inside it a stronger one, and the middle one a line.
        const yKeys = config.y || [];
        const y2Keys = config.y2 || [];

        const labels = data.map(row => toNumber(row[xKey]));

        const datasets = [];

        // --- Create datasets for bands ---
        // Each band needs TWO datasets: lower boundary + upper boundary with fill
        const bandCount = Math.floor(yKeys.length / 2);
        for (let i = 0; i < bandCount; i++) {
            const lower = yKeys[i];
            const upper = yKeys[yKeys.length - 1 - i];
            addBandDatasets(datasets, data, lower, upper,
                `${formatLabel(lower)}-${formatLabel(upper)}`, bandColor(i, bandCount));
        }

        // Median line (on top)
        if (yKeys.length % 2 === 1) {
            const middle = yKeys[(yKeys.length - 1) / 2];
            datasets.push({
                label: formatLabel(middle),
                data: data.map(row => toNumber(row[middle])),
                borderColor: PALETTE.medianLine,
                borderWidth: 2,
                pointRadius: 0,
                fill: false,
                tension: 0.4 // Smooth curves
            });
        }

        // Series on a second axis, for a quantity of a different kind - how many measurements
        // are behind the percentiles, say, which a band of three says something else than one
        // of three hundred
        y2Keys.forEach((key, index) => {
            datasets.push({
                label: formatLabel(key),
                data: data.map(row => toNumber(row[key])),
                borderColor: SECOND_AXIS_COLORS[index % SECOND_AXIS_COLORS.length],
                borderWidth: 1,
                borderDash: [4, 3],
                pointRadius: 0,
                fill: false,
                tension: 0.2,
                yAxisID: 'y2'
            });
        });
       
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
                            title: tooltipTitle,
                            label: context => {
                                const name = context.dataset.label || '';
                                // The second axis carries its own quantity, and its own format
                                const format = context.dataset.yAxisID === 'y2'
                                    ? config.y2Format : config.yFormat;
                                return (name ? name + ': ' : '')
                                    + tooltipValue(context.parsed.y, format);
                            },
                            // Hide tooltips for boundary lines
                            filter: item => !item.dataset.label.startsWith('_')
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
                                const firstLabel = this.getLabelForValue(this.min);
                                const lastLabel = this.getLabelForValue(this.max);
                                const range = Number(lastLabel) - Number(firstLabel);
                                return formatTickValue(label, range);
                            }
                        },
                        grid: { color: '#333', drawBorder: false }
                    },
                    y: {
                        title: {
                            display: true,
                            text: config.yLabel || yKeys.map(formatLabel).join(', '),
                            color: '#888'
                        },
                        ticks: { color: '#888', ...axisTicks(config.yFormat) },
                        grid: { color: '#333', drawBorder: false }
                    },
                    ...(y2Keys.length > 0 ? {
                        y2: {
                            type: 'linear',
                            position: 'right',
                            title: {
                                display: true,
                                text: config.y2Label || y2Keys.map(formatLabel).join(', '),
                                color: '#888'
                            },
                            ticks: { color: '#888', ...axisTicks(config.y2Format) },
                            grid: { drawOnChartArea: false }
                        }
                    } : {})
                }
            }
        };
        
        return new Chart(ctx, chartConfig);
    }
    
export function update(chart, data, config) {
        // For bands, just re-render (simpler than updating all datasets)
        if (chart) chart.destroy();
        const canvas = chart.canvas;
        return render(canvas, data, config);
    }
    
export function destroy(chart) {
        if (chart) {
            chart.destroy();
        }
    }

// Register with ChartRegistry
ChartRegistry.register('band-chart', { render, update, destroy });
