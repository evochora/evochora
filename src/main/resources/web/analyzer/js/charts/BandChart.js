import * as ChartRegistry from './ChartRegistry.js';
import { formatTickValue, axisTicks, tooltipTitle, tooltipValue } from './ChartUtils.js';

/**
 * Band Chart Implementation
 * 
 * Renders percentile data as layered bands to show distribution over time.
 * This is ideal for visualizing age distributions, showing min/max, interquartile range, etc.
 *
 * A metric may name its second-axis series through {@code labels}, a map from column to label,
 * and mark the rows it cannot judge yet through {@code tooYoung}, which names a column carrying
 * that mark; the chart then shows that stretch as such instead of leaving it looking empty.
 *
 * A metric may draw more than one distribution in one chart: {@code groups} names each of them
 * with its own colour and its own percentile columns, and the legend tells them apart by name.
 * Without it the chart draws the single distribution of its {@code y} columns.
 *
 * @module BandChart
 */
    
    // Bands get more opaque towards the middle, so the innermost is the most present
    const BAND_ALPHA = ['20', '40', '60', '80'];
    const BAND_BASE = '#4a9eff';

    /** How much room the label along the line needs across, in pixels. */
    const LABEL_LINE_HEIGHT = 13;

    /** The colour of the line the bands are held against, where a metric names one. */
    const REFERENCE_COLOR = '#9aa0a6';

    // The palette every chart of the analyzer hands out, by the position of the series
    const COLORS = [
        '#4a9eff', '#a0e0a0', '#ffb366', '#dda0dd', '#87ceeb',
        '#ffd700', '#ff6b6b', '#98d8c8', '#f08080', '#c79ecf'
    ];

    const SECOND_AXIS_COLORS = ['#ffb366', '#dda0dd'];

    /**
     * Colour of the nth band counted from the outside.
     *
     * @param {string} base - The hex colour of the group the band belongs to
     * @param {number} index - 0 for the outermost band
     * @param {number} total - How many bands there are
     * @returns {string} An rgba-style hex colour
     */
    // A band is shaded by how wide it is: the outermost is the faintest. A group of five
    // percentiles fills the ramp from the middle outwards, a group of one band takes the faintest,
    // which is what a chart holding several groups needs so their lines stay readable
    function bandColor(base, index, total) {
        // One band is the whole spread of its group, and several groups of one band share the
        // chart: it takes the faintest shading, so the lines stay the strongest thing on the plot
        if (total === 1) {
            return base + BAND_ALPHA[0];
        }
        const step = Math.max(0, BAND_ALPHA.length - total);
        return base + BAND_ALPHA[Math.min(BAND_ALPHA.length - 1, index + step)];
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
 * The band groups to draw: the ones the config names, or the single unnamed one of its {@code y}
 * columns.
 *
 * @param {Object} config - Visualization config
 * @returns {Array<{name: string, base: string, median: string, keys: Array<string>}>} Per group
 *          its name for the legend, the colour of its bands, the colour of its median line, and
 *          its percentile columns in ascending order
 */
function bandGroups(config) {
    if (Array.isArray(config.groups) && config.groups.length > 0) {
        return config.groups.map((group, index) => {
            // One group keeps the chart's own blue; several take the palette, so that a chart of
            // bands looks like every other chart of the analyzer
            const color = group.color
                || (config.groups.length > 1 ? COLORS[index % COLORS.length] : BAND_BASE);
            return {
                name: group.name || '',
                base: color,
                // The line and the bands around it are one quantity, so they are one colour
                median: color,
                keys: group.y || []
            };
        });
    }
    return [{ name: '', base: BAND_BASE, median: BAND_BASE, keys: config.y || [] }];
}

/**
 * The name one percentile column carries inside its group.
 *
 * A column named after its group - {@code energy_p50} in the group "Energy" - keeps only what
 * distinguishes it, so that the legend reads "Energy P50" rather than "Energy Energy P50".
 *
 * @param {string} name - The group's name, empty where the chart draws a single group
 * @param {string} key - The column
 * @returns {string} The label of that column
 */
/**
 * What a group of bands is called: its own name, or the name of the card where a chart draws only
 * one quantity and the group carries no name of its own.
 *
 * @param {Object} group - The band group
 * @param {Object} config - Visualization config, carrying the card's name
 * @returns {string} The name of the quantity the group draws
 */
function quantityOf(group, config) {
    if (group.name) {
        return formatLabel(group.name);
    }
    return config.metricName || config.yLabel || 'Value';
}

/**
 * The percentiles a group draws, where its columns name them, so that a tooltip reads
 * "Energy 10/25/50/75/90" rather than five column names. A group whose columns are not percentiles
 * - a value with a range around it, say - has none.
 *
 * @param {Object} group - The band group
 * @returns {Array<string>|null} The percentiles, or null where the columns name none
 */
function percentilesOf(group) {
    const parts = group.keys.map(key => key.split('_').pop().replace(/^p(?=\d)/i, ''));
    return parts.every(part => /^\d+$/.test(part)) ? parts : null;
}

function percentileLabel(name, key) {
    const stem = name.toLowerCase().replace(/\s+/g, '_');
    if (key.toLowerCase() === stem) {
        return '';
    }
    const prefix = stem + '_';
    const bare = name && key.toLowerCase().startsWith(prefix) ? key.slice(prefix.length) : key;
    return formatLabel(bare);
}

/**
 * Marks the stretch a metric says it cannot judge yet.
 *
 * A metric whose answer needs what happens after a moment - whether a birth founded a line, say -
 * has nothing to say about its newest moments. Their windows are empty, and an empty right edge
 * reads as data that went missing. The stretch is therefore drawn as what it is: shaded, closed
 * off by a line, and named.
 *
 * @param {Array<Object>} data - The rows drawn
 * @param {Object} config - Visualization config, naming the column that marks such a row
 * @returns {Array<Object>} The plugin, or none where the metric marks nothing
 */
function tooYoungPlugin(data, config) {
    const key = config.tooYoung;
    if (!key) {
        return [];
    }
    const first = data.findIndex(row => row[key]);
    if (first < 0) {
        return [];
    }
    return [{
        id: 'tooYoung',
        beforeDatasetsDraw(chart) {
            const area = chart.chartArea;
            const x = chart.scales.x.getPixelForValue(first);
            const ctx = chart.ctx;
            ctx.save();
            ctx.fillStyle = 'rgba(224, 224, 224, 0.09)';
            ctx.fillRect(x, area.top, area.right - x, area.bottom - area.top);
            ctx.strokeStyle = 'rgba(224, 224, 224, 0.4)';
            ctx.setLineDash([4, 4]);
            ctx.beginPath();
            ctx.moveTo(x, area.top);
            ctx.lineTo(x, area.bottom);
            ctx.stroke();
            ctx.setLineDash([]);
            // The label stands along the line and centred on it, where it needs the height of the
            // plot rather than its width: a stretch of a few windows is narrow, and a card is
            // never that short. Where even the height does not hold it, the shading and the line
            // say it alone
            const text = config.tooYoungLabel || 'too young to judge';
            ctx.fillStyle = '#9aa0a6';
            ctx.font = "10px 'Courier New', monospace";
            const height = area.bottom - area.top;
            if (ctx.measureText(text).width + 16 <= height) {
                const wide = area.right - x >= LABEL_LINE_HEIGHT;
                ctx.translate(x + (wide ? LABEL_LINE_HEIGHT - 3 : -3), (area.top + area.bottom) / 2);
                ctx.rotate(-Math.PI / 2);
                ctx.textAlign = 'center';
                ctx.fillText(text, 0, 0);
            }
            ctx.restore();
        }
    }];
}

/**
 * What the left axis is called where the metric does not name it: the quantities its groups draw,
 * or, where they carry no names, the name of the card. The columns are never used: a list of
 * percentile names is not the name of a quantity.
 *
 * @param {Array<{name: string, keys: Array<string>}>} groups - The band groups
 * @param {Object} config - Visualization config, carrying the card's name
 * @returns {string} The title of the axis
 */
function axisTitleOf(groups, config) {
    const named = groups.map(group => group.name).filter(Boolean);
    if (named.length > 0) {
        return named.map(formatLabel).join(', ');
    }
    // The quantity the card draws, never the list of its columns: an axis called
    // "P10, P25, P50, P75, P90" names five columns and no quantity at all
    return config.metricName || '';
}

/**
 * Puts a group's name in front of what one of its datasets is called.
 *
 * @param {string} name - The group's name, empty where the chart draws a single group
 * @param {string} text - What the dataset is called within its group
 * @returns {string} The label of the dataset
 */
function groupLabel(name, text) {
    if (!name) {
        return text;
    }
    return text ? `${formatLabel(name)} ${text}` : formatLabel(name);
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
        // A group's percentiles stand in ascending order, an odd number of them. Three, five and
        // seven all work; the outermost pair becomes the faintest band, each pair inside it a
        // stronger one, and the middle one a line.
        const groups = bandGroups(config);
        const y2Keys = config.y2 || [];
        const y2Colors = Array.isArray(config.y2Colors) && config.y2Colors.length > 0
            ? config.y2Colors : SECOND_AXIS_COLORS;

        const labels = data.map(row => toNumber(row[xKey]));

        const datasets = [];

        // --- Create datasets for bands ---
        // Each band needs TWO datasets: lower boundary + upper boundary with fill
        groups.forEach((group, groupIndex) => {
            const bandCount = Math.floor(group.keys.length / 2);
            for (let i = 0; i < bandCount; i++) {
                const lower = group.keys[i];
                const upper = group.keys[group.keys.length - 1 - i];
                const label = groupLabel(group.name,
                    percentileLabel(group.name, lower) + '-' + percentileLabel(group.name, upper));
                addBandDatasets(datasets, data, lower, upper, label,
                    bandColor(group.base, i, bandCount));
                datasets[datasets.length - 1].bandGroup = groupIndex;
                datasets[datasets.length - 2].bandGroup = groupIndex;
            }
        });

        // Median lines, above every band so that no group's shading covers another's middle
        groups.forEach((group, groupIndex) => {
            if (group.keys.length % 2 === 1) {
                const middle = group.keys[(group.keys.length - 1) / 2];
                datasets.push({
                    bandGroup: groupIndex,
                    isMedian: true,
                    // One entry per group, and it carries the quantity: the entry hides the whole
                    // group, so naming it after one of its percentiles would say the wrong thing
                    label: quantityOf(group, config),
                    data: data.map(row => toNumber(row[middle])),
                    borderColor: group.median,
                    borderWidth: 2,
                    pointRadius: 0,
                    fill: false,
                    tension: 0.4 // Smooth curves
                });
            }
        });

        // The value the bands are held against, where the metric names one: a dashed line without
        // a scale of its own, since it is the same quantity the left axis carries
        if (Number.isFinite(config.reference)) {
            datasets.push({
                label: config.referenceLabel || String(config.reference),
                data: data.map(() => config.reference),
                borderColor: REFERENCE_COLOR,
                borderDash: [6, 4],
                borderWidth: 1.5,
                pointRadius: 0,
                fill: false
            });
        }

        // Series on a second axis, for a quantity of a different kind - how many measurements
        // are behind the percentiles, say, which a band of three says something else than one
        // of three hundred. Dashed, unless the metric draws them as a quantity of its own.
        const seriesLabels = config.labels || {};
        y2Keys.forEach((key, index) => {
            datasets.push({
                // A metric may say what a series is rather than leave its column name to be read:
                // a column called p100 is the oldest organism, and on an axis of its own it has to
                // say so instead of naming the rank it happens to be
                label: seriesLabels[key] || formatLabel(key),
                data: data.map(row => toNumber(row[key])),
                borderColor: y2Colors[index % y2Colors.length],
                borderWidth: 1,
                ...(config.y2Solid ? {} : { borderDash: [4, 3] }),
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
            plugins: tooYoungPlugin(data, config),
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
                            // A group is one entry: its bands belong to its line and are hidden
                            // and shown with it, since a percentile on its own says nothing
                            filter: item => !item.text.startsWith('_') && !/-/.test(item.text)
                        },
                        onClick: (event, item, legend) => {
                            const chart = legend.chart;
                            const group = chart.data.datasets[item.datasetIndex].bandGroup;
                            const hidden = !chart.isDatasetVisible(item.datasetIndex);
                            chart.data.datasets.forEach((dataset, index) => {
                                if (dataset.bandGroup === group) {
                                    chart.setDatasetVisibility(index, hidden);
                                }
                            });
                            chart.update();
                        }
                    },
                    tooltip: {
                        backgroundColor: '#191923',
                        titleColor: '#e0e0e0',
                        bodyColor: '#aaa',
                        borderColor: '#333',
                        borderWidth: 1,
                        padding: 12,
                        // A group reads as one line, so only the dataset carrying its middle
                        // speaks; its bands and the boundary lines stay silent
                        filter: item => !item.dataset.label.startsWith('_')
                            && (item.dataset.bandGroup === undefined || item.dataset.isMedian),
                        callbacks: {
                            title: tooltipTitle,
                            label: context => {
                                const dataset = context.dataset;
                                // The second axis carries its own quantity, and its own format
                                const format = dataset.yAxisID === 'y2'
                                    ? config.y2Format : config.yFormat;
                                if (dataset.bandGroup === undefined) {
                                    const name = dataset.label || '';
                                    return (name ? name + ': ' : '')
                                        + tooltipValue(context.parsed.y, format);
                                }
                                // A group reads as one line: its percentiles belong together, and
                                // apart they say nothing about the spread they describe
                                const group = groups[dataset.bandGroup];
                                const row = data[context.dataIndex];
                                const value = key => tooltipValue(toNumber(row[key]), format);
                                const name = quantityOf(group, config);
                                const percentiles = percentilesOf(group);
                                if (percentiles) {
                                    return `${name} ${percentiles.join('/')}: `
                                        + group.keys.map(value).join(' / ');
                                }
                                // Three keys are a value and the range around it, and the range
                                // says what it is rather than leaving the reader to guess
                                const middle = group.keys[(group.keys.length - 1) / 2];
                                const ends = [group.keys[0], group.keys[group.keys.length - 1]];
                                const spread = config.bandLabel ? config.bandLabel + ' ' : '';
                                return `${name}: ${value(middle)} (${spread}`
                                    + `${value(ends[0])}\u2013${value(ends[1])})`;
                            },

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
                            text: config.yLabel || axisTitleOf(groups, config),
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
