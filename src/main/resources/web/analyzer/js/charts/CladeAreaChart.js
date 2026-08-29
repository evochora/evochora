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

/** Label of the band collecting genomes that belong to no shown clade. */
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
 * Without an open genome the bands are the roots of the lineage. Opening a genome replaces its
 * band by two kinds: the carriers of the genome itself, which would otherwise vanish into their
 * descendants, and one band per child clade. Siblings of an opened genome keep their bands.
 *
 * @param {Map<string, string|null>} parents - Genome to its parent
 * @param {Map<string, string[]>} children - Genome to its children
 * @param {string[]} openPath - Genomes opened, from the root downwards
 * @returns {{clades: Set<string>, selves: Set<string>}} Genomes standing for a whole clade, and
 *          genomes standing for their own carriers only
 */
function bandsFor(parents, children, openPath) {
    const clades = new Set();
    const selves = new Set();

    for (const [genome, parent] of parents) {
        if (parent == null || !parents.has(parent)) {
            clades.add(genome);
        }
    }

    for (const opened of openPath) {
        if (!clades.has(opened)) {
            break;
        }
        clades.delete(opened);
        selves.add(opened);
        for (const child of children.get(opened) || []) {
            clades.add(child);
        }
    }

    return { clades, selves };
}

/**
 * Maps every genome to the band it is counted in.
 *
 * A genome belongs to the nearest ancestor standing for a clade, or to itself where it stands for
 * its own carriers. A genome the lineage does not reach - its ancestry leaves the shown branches -
 * falls into the collecting band.
 *
 * @param {Iterable<string>} genomes - Genomes to map
 * @param {Map<string, string|null>} parents - Genome to its parent
 * @param {{clades: Set<string>, selves: Set<string>}} bands - Bands to map into
 * @returns {Map<string, string>} Genome to band key
 */
function mapToBands(genomes, parents, bands) {
    const bandOf = new Map();

    for (const genome of genomes) {
        if (bandOf.has(genome)) continue;

        const walked = [];
        let node = genome;
        let band = OTHER;

        while (node != null) {
            if (bands.selves.has(node)) {
                band = node === genome ? node : OTHER;
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
 * Sums the counts of each band per tick.
 *
 * @param {Array<Object>} data - Rows with tick, genome_hash, count
 * @param {Map<string, string>} bandOf - Genome to band key
 * @param {Map<string, string>} labels - Band key to label
 * @returns {Array<Object>} Rows with tick, clade, count
 */
function foldIntoBands(data, bandOf, labels) {
    const perTick = new Map();

    for (const row of data) {
        const tick = Number(row.tick);
        const band = bandOf.get(row.genome_hash) ?? OTHER;
        const label = labels.get(band) ?? band;

        if (!perTick.has(tick)) {
            perTick.set(tick, new Map());
        }
        const bands = perTick.get(tick);
        bands.set(label, (bands.get(label) || 0) + Number(row.count || 0));
    }

    const folded = [];
    for (const [tick, bands] of perTick) {
        for (const [clade, count] of bands) {
            folded.push({ tick, clade, count });
        }
    }
    return folded;
}

/**
 * Shortens a genome hash for display.
 *
 * The full hash identifies the genome; six base-36 digits are enough to tell the bands of one
 * chart apart, and short enough to read in a legend.
 *
 * @param {string} genome - Genome hash as text
 * @returns {string} A short label
 */
function shortLabel(genome) {
    try {
        let value = BigInt(genome);
        if (value < 0n) value = -value;
        return value.toString(36).slice(-6).padStart(6, '0');
    } catch (e) {
        return String(genome).slice(-6);
    }
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
 * Draws the path of opened genomes above the chart, each step clickable to go back.
 *
 * @param {HTMLCanvasElement} canvas - Canvas the chart draws into
 * @param {string[]} openPath - Genomes opened, from the root downwards
 * @param {Function} onOpen - Called with a new path when a step is clicked
 * @returns {HTMLElement} The path element
 */
function renderPath(canvas, openPath, onOpen) {
    const container = canvas.parentElement;
    let bar = container.querySelector('.clade-path');

    if (!bar) {
        bar = document.createElement('div');
        bar.className = 'clade-path';
        container.insertBefore(bar, canvas);
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
 * Renders the clade shares.
 *
 * @param {HTMLCanvasElement} canvas - Canvas element
 * @param {Array<Object>} data - Rows with tick, genome_hash, count
 * @param {Object} config - Visualization config
 * @param {Object} context - Render context with companion rows and view state
 * @returns {Chart|null} Chart.js instance, or null without a lineage to group by
 */
export function render(canvas, data, config, context = {}) {
    const lineage = context.companion;
    if (!lineage || lineage.length === 0) {
        return null;
    }

    const openPath = context.viewState?.openPath || [];
    const onViewStateChange = context.onViewStateChange || (() => {});

    const parents = buildParents(lineage);
    const children = buildChildren(parents);
    const bands = bandsFor(parents, children, openPath);
    const labels = labelBands(bands);

    const genomes = new Set(data.map(row => row.genome_hash));
    const bandOf = mapToBands(genomes, parents, bands);
    const folded = foldIntoBands(data, bandOf, labels);

    renderPath(canvas, openPath, (path) => onViewStateChange({ openPath: path }));

    const chart = StackedAreaChart.render(canvas, folded, {
        ...config,
        groupBy: 'clade',
        y: 'count'
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

    chart.options.onClick = (event, elements) => {
        if (elements.length === 0) return;
        const label = chart.data.datasets[elements[0].datasetIndex]?.label;
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
    const bar = chart?.canvas?.parentElement?.querySelector('.clade-path');
    if (bar) {
        bar.remove();
    }
    StackedAreaChart.destroy(chart);
}

ChartRegistry.register('clade-area-chart', { render, update, destroy });
