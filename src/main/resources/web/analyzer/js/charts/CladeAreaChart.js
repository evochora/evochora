import * as ChartRegistry from './ChartRegistry.js';
import * as StackedAreaChart from './StackedAreaChart.js';

/**
 * Clade Area Chart Implementation
 *
 * Shows how the living population divides between the branches of a lineage, as stacked areas.
 *
 * A clade is a genome together with everything descended from it. Which clades a chart shows is a
 * choice, not a property of the data: rooted at the first genome, every organism belongs to the
 * same clade and the chart is one full band. Rooted too deep, it is a thousand hairlines. The
 * choice is therefore made while looking - clicking a band replaces it with its child clades,
 * leaving its siblings as they are, so a cascade of sweeps can be followed one level at a time.
 *
 * The chart reads two tables: its own rows of `(tick, genome_hash, count)` and, as a companion,
 * the lineage rows of `(genome_hash, parent_genome_hash, first_birth_tick)`. Genome hashes arrive
 * as text, because 64 bits do not survive a JavaScript number.
 *
 * @module CladeAreaChart
 */

/** Label of the band collecting the clades too small to get one of their own. */
const OTHER = 'other';

/**
 * Builds the parent lookup of the lineage.
 *
 * A genome can arise more than once from different parents - the same mutation in different
 * lines gives the same hash - and the lineage keeps every edge. A tree needs one parent per node,
 * so the earliest edge wins: the line a genome first appeared in is the one it is drawn under.
 *
 * @param {Array<Object>} lineageRows - Companion rows with genome_hash, parent_genome_hash, first_birth_tick
 * @returns {Map<string, string|null>} Genome to its parent, null for a genome without one
 */
function buildParents(lineageRows) {
    const parents = new Map();
    const since = new Map();

    for (const row of lineageRows) {
        const genome = row.genome_hash;
        if (genome == null) continue;

        const birth = Number(row.first_birth_tick ?? 0);
        if (parents.has(genome) && since.get(genome) <= birth) {
            continue;
        }
        parents.set(genome, row.parent_genome_hash ?? null);
        since.set(genome, birth);
    }

    return parents;
}

/**
 * Collects the children of every genome that has any.
 *
 * @param {Map<string, string|null>} parents - Genome to its parent
 * @returns {Map<string, string[]>} Genome to its child genomes
 */
function buildChildren(parents) {
    const children = new Map();
    for (const [genome, parent] of parents) {
        if (parent == null) continue;
        if (!children.has(parent)) {
            children.set(parent, []);
        }
        children.get(parent).push(genome);
    }
    return children;
}

/**
 * Determines the bands to draw for an opened path.
 *
 * Without an open genome the bands are the roots of the lineage. Opening one shows what is inside
 * it and nothing else: a band per child clade, plus one for the carriers of the genome itself,
 * which would otherwise vanish into its descendants. Going deeper is entering, not unfolding -
 * what lies outside the opened genome leaves the picture, and the path leads back out.
 *
 * @param {Map<string, string|null>} parents - Genome to its parent
 * @param {Map<string, string[]>} children - Genome to its children
 * @param {string[]} openPath - Genomes opened, from the root downwards
 * @returns {{clades: Set<string>, selves: Set<string>}} Genomes standing for a whole clade, and
 *          genomes standing for their own carriers only
 */
function bandsFor(parents, children, openPath) {
    if (openPath.length === 0) {
        const roots = new Set();
        for (const [genome, parent] of parents) {
            if (parent == null || !parents.has(parent)) {
                roots.add(genome);
            }
        }
        return { clades: roots, selves: new Set() };
    }

    const opened = openPath[openPath.length - 1];
    return {
        clades: new Set(children.get(opened) || []),
        selves: new Set([opened])
    };
}

/**
 * Maps every genome to the band it is counted in.
 *
 * A genome belongs to the nearest ancestor standing for a clade, or to itself where it stands for
 * its own carriers. A genome whose ancestry leaves the opened branch belongs to no band and is
 * left out of the picture - it is still population, and still counts towards the shares.
 *
 * @param {Iterable<string>} genomes - Genomes to map
 * @param {Map<string, string|null>} parents - Genome to its parent
 * @param {{clades: Set<string>, selves: Set<string>}} bands - Bands to map into
 * @returns {Map<string, string|null>} Genome to band key, null for genomes outside the branch
 */
function mapToBands(genomes, parents, bands) {
    const bandOf = new Map();

    for (const genome of genomes) {
        if (bandOf.has(genome)) continue;

        const walked = [];
        let node = genome;
        let band = null;

        while (node != null) {
            if (bands.selves.has(node)) {
                band = node === genome ? node : null;
                break;
            }
            if (bands.clades.has(node)) {
                band = node;
                break;
            }
            if (bandOf.has(node)) {
                band = bandOf.get(node);
                break;
            }
            walked.push(node);
            const next = parents.get(node);
            if (next === node) break;
            node = next === undefined ? null : next;
        }

        bandOf.set(genome, band);
        for (const seen of walked) {
            if (seen !== genome) {
                bandOf.set(seen, band);
            }
        }
    }

    return bandOf;
}

/**
 * Sums the counts of each band per tick, keeping the largest and collecting the rest.
 *
 * A branch of the lineage has as many children as mutation gave it - a hundred and more in a run
 * of any length - and a chart of a hundred bands is a legend with a sliver of chart under it. The
 * largest few carry what there is to see; everything else is one band, which stays honest because
 * the shares still add up to the whole population.
 *
 * @param {Array<Object>} data - Rows with tick, genome_hash, count
 * @param {Map<string, string>} bandOf - Genome to band key
 * @param {Map<string, string>} labels - Band key to label
 * @param {number} maxBands - Greatest number of named bands to keep
 * @returns {Array<Object>} Rows with tick, clade, count
 */
function foldIntoBands(data, bandOf, labels, maxBands) {
    const perTick = new Map();
    const population = new Map();
    const totals = new Map();

    for (const row of data) {
        const tick = Number(row.tick);
        const count = Number(row.count || 0);
        population.set(tick, (population.get(tick) || 0) + count);

        // Genomes outside the opened genome are not shown, but they are still population and
        // count towards what a share is a share of
        const band = bandOf.get(row.genome_hash);
        if (band == null) {
            continue;
        }
        const label = labels.get(band) ?? band;

        if (!perTick.has(tick)) {
            perTick.set(tick, new Map());
        }
        const bands = perTick.get(tick);
        bands.set(label, (bands.get(label) || 0) + count);
        totals.set(label, (totals.get(label) || 0) + count);
    }

    // Ranked over the whole window, so a band does not appear and vanish from tick to tick
    const kept = new Set([...totals.entries()]
        .sort((a, b) => b[1] - a[1])
        .slice(0, maxBands)
        .map(([label]) => label));

    const folded = [];
    for (const [tick, bands] of perTick) {
        const whole = population.get(tick) || 0;
        if (whole === 0) continue;

        let other = 0;
        for (const [clade, count] of bands) {
            if (kept.has(clade)) {
                folded.push({ tick, clade, share: (count / whole) * 100 });
            } else {
                other += count;
            }
        }
        if (other > 0) {
            folded.push({ tick, clade: OTHER, share: (other / whole) * 100 });
        }
    }
    return folded;
}

/** Digits of the six-character genome label, as the rest of the project writes it. */
const BASE62 = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';

/**
 * Shortens a genome hash to the label used everywhere a genome is named.
 *
 * The full hash identifies the genome; six base-62 digits of its unsigned value are enough to tell
 * the bands of one chart apart. The same six characters name a genome in the analysis scripts, so
 * a band and a number in a notebook are recognisably the same thing.
 *
 * @param {string} genome - Genome hash as text, signed as it comes out of a 64-bit column
 * @returns {string} A six-character label
 */
function shortLabel(genome) {
    let value;
    try {
        value = BigInt(genome);
    } catch (e) {
        return String(genome).slice(-6);
    }

    if (value < 0n) {
        value += 1n << 64n;
    }

    let label = '';
    for (let i = 0; i < 6; i++) {
        label = BASE62[Number(value % 62n)] + label;
        value /= 62n;
    }
    return label;
}

/**
 * Builds the labels of the bands, marking those that stand for carriers only.
 *
 * @param {{clades: Set<string>, selves: Set<string>}} bands - Bands to label
 * @returns {Map<string, string>} Band key to label
 */
function labelBands(bands) {
    const labels = new Map();
    for (const genome of bands.clades) {
        labels.set(genome, shortLabel(genome));
    }
    for (const genome of bands.selves) {
        labels.set(genome, `${shortLabel(genome)} (itself)`);
    }
    return labels;
}

/**
 * Draws the path of opened genomes under the chart, each step clickable to go back.
 *
 * @param {HTMLCanvasElement} canvas - Canvas the chart draws into
 * @param {string[]} openPath - Genomes opened, from the root downwards
 * @param {Function} onOpen - Called with a new path when a step is clicked
 * @returns {HTMLElement} The path element
 */
function renderPath(canvas, openPath, onOpen) {
    // Below the chart container, not inside it: laid over the canvas the path covers the axis
    const container = canvas.parentElement;
    const card = container.parentElement;
    let bar = card.querySelector('.clade-path');

    if (!bar) {
        bar = document.createElement('div');
        bar.className = 'clade-path';
        container.insertAdjacentElement('afterend', bar);
    }

    bar.replaceChildren();

    const steps = [{ label: 'all clades', path: [] }];
    openPath.forEach((genome, index) => {
        steps.push({ label: shortLabel(genome), path: openPath.slice(0, index + 1) });
    });

    steps.forEach((step, index) => {
        if (index > 0) {
            const separator = document.createElement('span');
            separator.className = 'clade-path-separator';
            separator.textContent = ' › ';
            bar.appendChild(separator);
        }
        const button = document.createElement('button');
        button.className = 'clade-path-step';
        button.textContent = step.label;
        button.disabled = index === steps.length - 1;
        button.addEventListener('click', () => onOpen(step.path));
        bar.appendChild(button);
    });

    return bar;
}

/**
 * The band a point in the plot belongs to, by walking up the stack.
 *
 * @param {Chart} chart - The rendered chart
 * @param {number} x - Click position on the canvas
 * @param {number} y - Click position on the canvas
 * @returns {string|null} Label of the band under that point, or null outside the plot
 */
function bandAt(chart, x, y) {
    const area = chart.chartArea;
    if (x < area.left || x > area.right || y < area.top || y > area.bottom) {
        return null;
    }

    const index = Math.round(chart.scales.x.getValueForPixel(x));
    const value = chart.scales.y.getValueForPixel(y);

    let stacked = 0;
    for (const dataset of chart.data.datasets) {
        stacked += Number(dataset.data[index]) || 0;
        if (value <= stacked) {
            return dataset.label;
        }
    }
    return null;
}

/**
 * Renders the clade shares.
 *
 * @param {HTMLCanvasElement} canvas - Canvas element
 * @param {Array<Object>} data - Rows with tick, genome_hash, count
 * @param {Object} config - Visualization config; {@code maxBands} caps the named bands (default 8)
 * @param {Object} context - Render context with companion rows and view state
 * @returns {Chart|null} Chart.js instance, or null without a lineage to group by
 */
export function render(canvas, data, config, context = {}) {
    const lineage = context.companion;
    if (!lineage || lineage.length === 0) {
        return null;
    }

    const onViewStateChange = context.onViewStateChange || (() => {});

    const parents = buildParents(lineage);
    const children = buildChildren(parents);

    // A run starts from one genome, so the unopened chart would be a single band filling the
    // plot - true and useless. Without a choice made yet, that one root is opened, which is
    // where there is something to see. Going back to it stays possible through the path.
    let openPath = context.viewState?.openPath;
    if (openPath === undefined) {
        const roots = bandsFor(parents, children, []).clades;
        openPath = roots.size === 1 ? [...roots] : [];
    }

    const bands = bandsFor(parents, children, openPath);
    const labels = labelBands(bands);

    const genomes = new Set(data.map(row => row.genome_hash));
    const bandOf = mapToBands(genomes, parents, bands);
    const folded = foldIntoBands(data, bandOf, labels, config.maxBands || 8);

    renderPath(canvas, openPath, (path) => onViewStateChange({ openPath: path }));

    // Shares are computed here against the whole population, so the axis is a plain scale that
    // ends where the opened branch ends - not a percentage of what happens to be shown, which
    // would make every clade fill the plot as soon as it is entered. It still runs to 100, the
    // share the whole population stands for: an axis fitted to the bands would hide how much of
    // the population the opened branch actually is.
    const chart = StackedAreaChart.render(canvas, folded, {
        ...config,
        groupBy: 'clade',
        y: 'share',
        yAxisMode: undefined,
        yMax: 100,
        yFormat: 'percent'
    });

    if (!chart) {
        return chart;
    }

    // A band standing for a clade with children can be opened; one standing for carriers cannot
    const openableByLabel = new Map();
    for (const genome of bands.clades) {
        if ((children.get(genome) || []).length > 0) {
            openableByLabel.set(labels.get(genome), genome);
        }
    }

    // Which band was clicked follows from where in the stack the click landed. The elements
    // Chart.js reports are of no use here: the interaction mode is "index", so a click returns
    // every band at that tick, and the first of them is always the bottom one.
    chart.options.onClick = (event, elements, clicked) => {
        const label = bandAt(clicked, event.x, event.y);
        const genome = openableByLabel.get(label);
        if (genome) {
            onViewStateChange({ openPath: [...openPath, genome] });
        }
    };
    chart.update('none');

    return chart;
}

/**
 * Updates an existing chart. The clade grouping is rebuilt on every render, so an update redraws.
 *
 * @param {Chart} chart - Chart.js instance
 * @param {Array<Object>} data - New data
 * @param {Object} config - Visualization config
 * @returns {Chart} The chart
 */
export function update(chart, data, config) {
    return StackedAreaChart.update(chart, data, config);
}

/**
 * Destroys the chart and removes the path above it.
 *
 * @param {Chart} chart - Chart.js instance
 */
export function destroy(chart) {
    const bar = chart?.canvas?.closest('.metric-card')?.querySelector('.clade-path');
    if (bar) {
        bar.remove();
    }
    StackedAreaChart.destroy(chart);
}

ChartRegistry.register('clade-area-chart', { render, update, destroy });
