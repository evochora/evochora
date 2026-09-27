/**
 * The colours of descent: how an organism is coloured by the line it descends from.
 *
 * One organism, or the virtual root `all` above the founders, is the root. Every child of the
 * root is a line; every organism of a tick belongs to the line it descends from, lies outside
 * the root's descent, or is not known yet. The server attributes the organisms of a tick and
 * ranks the lines by size (the `descent` object of the organisms-of-a-tick answer); this module
 * turns that answer into colours. It holds no application state and touches no DOM, so the
 * environment grid, the organism list, the minimap and the descent section all colour through it.
 *
 * The ancestry of one organism is coloured by genome depth: how many genome changes lie between
 * an ancestor and the organism. The same ramp colours the ancestry strip and the mutation marks.
 *
 * @module DescentColours
 */

/** The line palette, in the order of its indices; the subsets below pick from it. */
export const LINE_PALETTE = Object.freeze([
    '#ff3b3b', '#ff9f00', '#ffe600', '#5cff3b', '#00ffc8',
    '#00c2ff', '#3b5cff', '#b23bff', '#ff3bc8', '#ff3b7a'
]);

/**
 * Palette indices taken by the coloured lines, keyed by their number: the n-th largest line takes
 * the n-th index. The server colours at most eight lines.
 */
export const LINE_PALETTE_SUBSETS = Object.freeze({
    1: Object.freeze([3]),
    2: Object.freeze([3, 8]),
    3: Object.freeze([1, 4, 8]),
    4: Object.freeze([1, 3, 6, 8]),
    5: Object.freeze([1, 2, 4, 6, 8]),
    6: Object.freeze([0, 1, 3, 5, 7, 8]),
    7: Object.freeze([0, 1, 2, 4, 5, 7, 9]),
    8: Object.freeze([0, 1, 2, 3, 5, 6, 7, 9])
});

/** The tones outside the line palette, as CSS hex strings. */
export const DESCENT_TONES = Object.freeze({
    /** A line beyond the coloured ones. */
    OTHER: '#8f9bb3',
    /** An organism not descended from the root. */
    OUTSIDE: '#555555',
    /** An organism whose ancestry is not known yet. */
    UNKNOWN: '#38405a',
    /** A dead organism, wherever it is shown. */
    DEAD: '#7a3535',
    /** The root itself while it is alive. */
    ROOT: '#ffffff',
    /** The selected organism. */
    SELECTED: '#ffffff'
});

/** Line value of an organism that is not descended from the root. */
const OUTSIDE_LINE = 0;

/** Line value of an organism whose ancestry is not known. */
const UNKNOWN_LINE = -1;

/**
 * Returns the palette colour of a coloured line.
 *
 * @param {number} rank - The line's rank among the coloured lines, 0 for the largest.
 * @param {number} colouredLines - How many lines are coloured, 1 to 8.
 * @returns {string} The colour as a CSS hex string; the tone of other lines for a rank outside
 *     the palette.
 */
export function lineColour(rank, colouredLines) {
    const subset = LINE_PALETTE_SUBSETS[colouredLines];
    const index = subset ? subset[rank] : rank;
    return LINE_PALETTE[index] ?? DESCENT_TONES.OTHER;
}

/**
 * Converts a CSS hex colour to a packed RGB integer.
 *
 * @param {string} hex - A colour of the form `#rrggbb`.
 * @returns {number} The colour as 0xRRGGBB.
 */
export function hexToInt(hex) {
    return parseInt(hex.slice(1), 16);
}

/**
 * The colouring of the organisms of one tick against one root.
 * <p>
 * Built from the `descent` object of an organisms-of-a-tick answer. A descent that is missing,
 * still loading or failed carries no lines, and every living organism then takes the unknown tone.
 * Immutable once built; a new answer makes a new colouring.
 */
export class DescentColouring {
    /**
     * Builds the colouring of one answer.
     *
     * @param {object|null} descent - The `descent` object of the answer, or null when there is none.
     */
    constructor(descent) {
        /** Id of the root, 0 for `all`, null while the root is not known. */
        this.rootId = descent?.root ? descent.root.id : null;
        this._lineOf = descent?.lineOf ?? {};
        const lines = Array.isArray(descent?.lines) ? descent.lines : [];
        const coloured = lines.filter(line => line.colour !== null && line.colour !== undefined);
        this._lineColours = new Map(coloured.map(line => [line.id, lineColour(line.colour, coloured.length)]));
        this._ints = new Map();
    }

    /**
     * Returns the colour of a line: its palette colour, or the tone of other lines.
     *
     * @param {number} lineId - The id of the child of the root that heads the line.
     * @returns {string} The colour as a CSS hex string.
     */
    lineColourOf(lineId) {
        return this._lineColours.get(lineId) ?? DESCENT_TONES.OTHER;
    }

    /**
     * Returns the colour of an organism of the tick the colouring was built for.
     * <p>
     * A dead organism takes the dead tone and the living root white; every other organism the
     * colour of its line, the outside tone, or the unknown tone when its ancestry is not known.
     * The selected organism is not singled out here: where the selection is drawn in white is
     * decided by the view.
     *
     * @param {number} organismId - The organism's id.
     * @param {boolean} isDead - Whether the organism is dead at the tick.
     * @returns {string} The colour as a CSS hex string.
     */
    colourOf(organismId, isDead) {
        if (isDead) {
            return DESCENT_TONES.DEAD;
        }
        if (this.rootId !== null && this.rootId > 0 && organismId === this.rootId) {
            return DESCENT_TONES.ROOT;
        }
        const line = this._lineOf[organismId];
        if (line === undefined || line === null || line === UNKNOWN_LINE) {
            return DESCENT_TONES.UNKNOWN;
        }
        if (line === OUTSIDE_LINE) {
            return DESCENT_TONES.OUTSIDE;
        }
        return this.lineColourOf(line);
    }

    /**
     * Returns the colour of an organism as a packed RGB integer, as the canvas renderers take it.
     *
     * @param {number} organismId - The organism's id.
     * @param {boolean} isDead - Whether the organism is dead at the tick.
     * @returns {number} The colour as 0xRRGGBB.
     */
    colourIntOf(organismId, isDead) {
        const hex = this.colourOf(organismId, isDead);
        let value = this._ints.get(hex);
        if (value === undefined) {
            value = hexToInt(hex);
            this._ints.set(hex, value);
        }
        return value;
    }

    /**
     * Tells whether an organism of the tick lies outside the root's descent.
     *
     * @param {number} organismId - The organism's id.
     * @returns {boolean} True when the answer places the organism outside the root's descent.
     */
    isOutside(organismId) {
        return this._lineOf[organismId] === OUTSIDE_LINE;
    }
}

/** Colour of the youngest genome change of an ancestry, as [r, g, b]. */
const DEPTH_YOUNG = Object.freeze([0x00, 0xff, 0xc8]);

/** Colour of the oldest genome change of an ancestry, as [r, g, b]. */
const DEPTH_OLD = Object.freeze([0xff, 0x3b, 0xc8]);

/**
 * Returns the colour of a genome depth as a packed RGB integer: cyan for the youngest change,
 * magenta for the oldest, interpolated in RGB over depth / (changes - 1). A single change is cyan.
 *
 * @param {number} depth - Genome changes between the ancestor and the organism, 0 for the youngest.
 * @param {number} changes - Genome changes along the whole ancestry, the founder included.
 * @returns {number} The colour as 0xRRGGBB.
 */
export function depthColourInt(depth, changes) {
    const t = changes > 1 ? Math.min(1, Math.max(0, depth / (changes - 1))) : 0;
    const channel = (i) => Math.round(DEPTH_YOUNG[i] + (DEPTH_OLD[i] - DEPTH_YOUNG[i]) * t);
    return (channel(0) << 16) | (channel(1) << 8) | channel(2);
}

/**
 * Returns the colour of a genome depth as a CSS hex string; see {@link depthColourInt}.
 *
 * @param {number} depth - Genome changes between the ancestor and the organism, 0 for the youngest.
 * @param {number} changes - Genome changes along the whole ancestry, the founder included.
 * @returns {string} The colour as `#rrggbb`.
 */
export function depthColour(depth, changes) {
    return `#${depthColourInt(depth, changes).toString(16).padStart(6, '0')}`;
}

/**
 * Reads the genome changes along the ancestry of one organism.
 * <p>
 * The entries run from the organism itself to the oldest known ancestor. An entry carries a
 * genome change when its genome differs from its parent's; consecutive equal genomes are one
 * genome, and the oldest entry, the founder, always counts as a change. Depth 0 is the youngest
 * change, the organism's own genome.
 *
 * @param {{organismId: number, genomeHash: (string|number)}} organism - The organism whose
 *     ancestry is read.
 * @param {Array<{organismId: number, genomeHash: (string|number)}>} lineage - Its ancestors,
 *     direct parent first, oldest last.
 * @returns {{organismId: number, entries: Array<object>, changes: number,
 *     byOrganism: Map<number, number>}} The organism's id; every entry with its organism id,
 *     genome hash, generations back (`back`), whether it carries a change and, if so, its depth;
 *     the number of changes; and the depth of each organism of the chain that carries a change.
 */
export function buildGenomeChain(organism, lineage) {
    const entries = [
        { organismId: organism.organismId, genomeHash: String(organism.genomeHash) },
        ...(lineage || []).map(entry => ({ organismId: entry.organismId, genomeHash: String(entry.genomeHash) }))
    ];
    const byOrganism = new Map();
    let changes = 0;
    entries.forEach((entry, index) => {
        const parent = entries[index + 1];
        entry.back = index;
        entry.change = parent === undefined || parent.genomeHash !== entry.genomeHash;
        if (entry.change) {
            entry.depth = changes++;
            byOrganism.set(entry.organismId, entry.depth);
        }
    });
    return { organismId: organism.organismId, entries, changes, byOrganism };
}
