import { ValueFormatter } from './utils/ValueFormatter.js';

/**
 * The clade tree of a run and the level of it that is currently entered.
 *
 * Holds no DOM. What it answers is a colour per genome and, per sampled tick, the shares its
 * bands hold there — everything the chart and the grid need, and nothing about how they draw it.
 *
 * @class CladeModel
 */

/**
 * How many clades a level shows at most.
 * <p>
 * A branch has as many children as mutation gave it, and a chart of a hundred bands is a legend
 * with a sliver of chart under it. The largest carry what there is to see, the rest are one band.
 * <p>
 * Eight, because that is where the curated subsets of the palette end: beyond it the palette
 * repeats and two bands of a level would share a colour.
 */
const BANDS = 8;

/** Rainbow order; which of these are used when fewer are needed is decided by {@link SUBSETS}. */
const PALETTE = ['#ff3b3b', '#ff9f00', '#ffe600', '#5cff3b', '#00ffc8', '#00c2ff', '#3b5cff',
                 '#b23bff', '#ff3bc8', '#ff3b7a'];

/**
 * Which colours drop out when a level has fewer clades than the palette holds. The ones that stay
 * keep their order, so a band never changes its neighbours' hues by appearing.
 */
const SUBSETS = {
    1: [3],
    2: [3, 8],
    3: [1, 4, 8],
    4: [1, 3, 6, 8],
    5: [1, 2, 4, 6, 8],
    6: [0, 1, 3, 5, 7, 8],
    7: [0, 1, 2, 4, 5, 7, 9],
    8: [0, 1, 2, 3, 5, 6, 7, 9]
};

/** The band that is the opened clade itself and what it absorbed; never a clade one can open. */
export const OTHER_COLOUR = '#8f9bb3';

/** Everything outside the opened clade: dark enough to step back, distinct from the dead grey. */
export const OUTSIDE_COLOUR = '#38405a';


export class CladeModel {

    /** Everything outside the opened clade, and every genome while no tree is known yet. */
    static OUTSIDE = parseInt(OUTSIDE_COLOUR.slice(1), 16);

    /**
     * Builds the tree from what the clade endpoint answered.
     *
     * @param {{genomes: string[], parents: number[], samples: Array<{tick: number, carriers: number[][]}>}} answer
     *        Genomes named once and referred to by position, with the population at sampled ticks.
     * @param {function(string): (string|null|undefined)} [lookUpParent] Answers for a genome the
     *        tree does not hold; see {@link #_chainOf}.
     */
    constructor(answer, lookUpParent = () => undefined) {
        /** @type {Map<string, string|null>} genome → parent genome, null where a line begins */
        this._parents = new Map();
        /** @type {Map<string, Set<string>>} genome → its children */
        this._children = new Map();
        /** @type {string[]} the genomes whose parent the run does not hold */
        this._roots = [];
        /** @type {Map<number, Map<string, number>>} sampled tick → genome → carriers */
        this._population = new Map();
        /** @type {string[]} the founders entered, outermost first */
        this._path = [];
        this._weight = new Map();
        this._level = null;
        this._labels = null;
        this._lookUpParent = lookUpParent;

        const genomes = answer?.genomes ?? [];
        const parents = answer?.parents ?? [];
        genomes.forEach((genome, i) => {
            const parent = parents[i];
            this._parents.set(genome, parent >= 0 ? genomes[parent] : null);
        });
        for (const sample of answer?.samples ?? []) {
            const counts = new Map();
            for (const [index, carriers] of sample.carriers) {
                counts.set(genomes[index], carriers);
            }
            this._population.set(sample.tick, counts);
        }

        this._index();
    }

    /** The sampled ticks, ascending. @returns {number[]} */
    get stops() {
        return [...this._population.keys()].sort((a, b) => a - b);
    }

    /** The founders entered so far, outermost first. @returns {string[]} */
    get path() {
        return [...this._path];
    }

    /** The bands of the level currently entered. @returns {Array<object>} */
    get bands() {
        return this._levelOf().list;
    }

    /** Whether a clade is entered at all, as opposed to standing above the roots of the run. */
    get isEntered() {
        return this._path.length > 0;
    }

    /**
     * Enters a band, which becomes the level shown.
     *
     * @param {object} band One of {@link #bands}, of kind {@code clade}
     */
    enter(band) {
        if (!band || band.kind !== 'clade') {
            return;
        }
        this._path.push(band.founder);
        this._level = null;
    }

    /**
     * Enters the clade of a genome named by its label, wherever in the tree it sits.
     * <p>
     * The path becomes the ancestry of that genome, so the view arrives where a sequence of
     * clicks would have led. Labels are the six characters a genome is written with everywhere
     * else; they are the tail of a 64-bit hash and could in principle name two genomes, in which
     * case the one carrying more organisms wins.
     *
     * @param {string} label Six characters as {@link ValueFormatter#formatGenomeHash} writes them
     * @returns {boolean} Whether a genome of that label is in the tree
     */
    enterByLabel(label) {
        const wanted = String(label).trim();
        if (!wanted) {
            this.backTo(0);
            return true;
        }
        const found = this._byLabel().get(wanted) ?? null;
        if (found === null) {
            return false;
        }
        // The chain runs from the genome upwards; the path runs from the root down to it
        this._path = this._chainOf(found).reverse();
        this._level = null;
        return true;
    }

    /**
     * Completes a label that only one genome of the run begins with.
     *
     * @param {string} prefix What has been typed so far
     * @returns {string|null} The whole label, or null where nothing or more than one matches
     */
    completeLabel(prefix) {
        const typed = String(prefix).trim();
        if (!typed) {
            return null;
        }
        let only = null;
        for (const label of this._byLabel().keys()) {
            if (!label.startsWith(typed)) {
                continue;
            }
            if (only !== null) {
                return null;
            }
            only = label;
        }
        return only;
    }

    /**
     * Every genome of the run by its label, built once.
     * <p>
     * A label is the tail of a 64-bit hash and could name two genomes; the one carrying more
     * organisms wins, because that is the one a viewer means.
     * @private
     */
    _byLabel() {
        if (this._labels) {
            return this._labels;
        }
        this._labels = new Map();
        for (const genome of this._parents.keys()) {
            const label = ValueFormatter.formatGenomeHash(genome);
            const other = this._labels.get(label);
            if (other === undefined || this._weightOf(genome) > this._weightOf(other)) {
                this._labels.set(label, genome);
            }
        }
        return this._labels;
    }

    /**
     * What share of the given genomes each step of the path carries, the root of the tree first.
     * <p>
     * A step carries everything descended from it, so the shares only ever grow towards the root.
     * The genomes are those on screen, which is why this is asked again at every tick.
     *
     * @param {Iterable<number|bigint|string>} genomeHashes The genomes of the organisms shown
     * @returns {number[]} One share in [0, 1] per step, {@code all} first
     */
    sharesAlongPath(genomeHashes) {
        const at = new Map();
        this._path.forEach((genome, i) => at.set(genome, i + 1));
        const carried = new Array(this._path.length + 1).fill(0);
        let total = 0;
        for (const hash of genomeHashes) {
            total++;
            carried[0]++;                        // everything is under the root of the tree
            for (const node of this._chainOf(String(hash))) {
                const step = at.get(node);
                if (step !== undefined) {
                    carried[step]++;
                }
            }
        }
        return carried.map(count => (total > 0 ? count / total : 0));
    }

    /**
     * How many clades stand under a step of the path: the children that carried anything.
     *
     * @param {number} step Position in the path; zero is the root of the tree
     * @returns {number} The number of clades one level down
     */
    cladesUnderStep(step) {
        const opened = step === 0 ? null : (this._path[step - 1] ?? null);
        return this._cladesUnder(opened).filter(genome => this._weightOf(genome) > 0).length;
    }

    /** The label of the level currently entered, empty above the roots of the run. */
    get label() {
        const entered = this._path[this._path.length - 1];
        return entered ? ValueFormatter.formatGenomeHash(entered) : '';
    }

    /**
     * Goes back to the level of the given depth; zero is above all clades.
     *
     * @param {number} depth How many steps of the path to keep
     */
    backTo(depth) {
        this._path = this._path.slice(0, Math.max(0, depth));
        this._level = null;
    }

    /**
     * The colour of a genome as a packed RGB integer.
     *
     * @param {number|bigint|string} genomeHash The genome to colour
     * @returns {number} Packed RGB, the outside tone for a genome beyond the entered clade
     */
    colourOf(genomeHash) {
        const band = this.bandOf(genomeHash);
        if (band) {
            return CladeModel._asInt(band.colour ?? OTHER_COLOUR);
        }
        return CladeModel._asInt(OUTSIDE_COLOUR);
    }

    /**
     * The band a genome belongs to on the level entered, or null when it lies outside it.
     *
     * @param {number|bigint|string} genomeHash The genome to place
     * @returns {object|null} One of {@link #bands}
     */
    bandOf(genomeHash) {
        const level = this._levelOf();
        for (const node of this._chainOf(String(genomeHash ?? 0))) {
            const band = level.byFounder.get(node);
            if (band) {
                return band;
            }
        }
        return null;
    }

    /**
     * The stacked shares per sampled tick, bottom band first.
     * <p>
     * Computed for a level, not for a tick or a size: walking a chain per genome per sample is
     * far too much to repeat on every draw.
     *
     * @returns {Array<{tick: number, tops: number[], total: number}>} Cumulative shares in [0, 1]
     */
    stacks() {
        const level = this._levelOf();
        if (level.stacks) {
            return level.stacks;
        }
        level.stacks = this.stops.map(tick => {
            const counts = this._population.get(tick) ?? new Map();
            const shares = new Array(level.list.length).fill(0);
            let total = 0;
            for (const [genome, carriers] of counts) {
                total += carriers;
                const band = this.bandOf(genome);
                if (band) {
                    shares[level.list.indexOf(band)] += carriers;
                }
            }
            const tops = [];
            let acc = 0;
            for (const share of shares) {
                acc += share / Math.max(1, total);
                tops.push(acc);
            }
            return { tick, tops, total };
        });
        return level.stacks;
    }

    /**
     * Collects the children of every genome and the roots of the run — those whose parent the
     * answer does not hold. A run has more than one root more often than not.
     * <p>
     * Called once, from the constructor. The tree describes the run as the answer found it and
     * does not change afterwards, which is what lets a level and its shares be computed once.
     * @private
     */
    _index() {
        this._children = new Map();
        this._roots = [];
        for (const [genome, parent] of this._parents) {
            if (parent == null || !this._parents.has(parent)) {
                this._roots.push(genome);
                continue;
            }
            if (!this._children.has(parent)) {
                this._children.set(parent, new Set());
            }
            this._children.get(parent).add(genome);
        }
    }

    /**
     * The genome and its ancestors, nearest first.
     * <p>
     * The tree is built from sampled ticks, while the tick on screen holds genomes that arose
     * between two of them. Those are looked up instead of being added: a tree that changed with
     * the tick would have to have its level and all its shares computed again at every step.
     * @private
     */
    _chainOf(genome) {
        const chain = [];
        const seen = new Set();
        let current = genome;
        while (current && current !== '0' && !seen.has(current)) {
            chain.push(current);
            seen.add(current);
            current = this._parents.has(current)
                ? this._parents.get(current)
                : (this._lookUpParent(current) ?? null);
        }
        return chain;
    }

    /** How many carriers a node and everything below it had, over all samples. @private */
    _weightOf(node) {
        if (this._weight.has(node)) {
            return this._weight.get(node);
        }
        let sum = 0;
        for (const counts of this._population.values()) {
            sum += counts.get(node) ?? 0;
        }
        this._weight.set(node, sum);           // guards against a cycle in a malformed tree
        for (const child of this._children.get(node) ?? []) {
            sum += this._weightOf(child);
        }
        this._weight.set(node, sum);
        return sum;
    }

    /**
     * The clades of a level, heaviest first: the children of the opened genome, or the roots of
     * the run when none is opened.
     * <p>
     * Ranked over the whole run rather than per tick, so that a band does not appear and vanish
     * from one sample to the next.
     * @private
     */
    _cladesUnder(opened) {
        const under = opened === null ? this._roots : [...(this._children.get(opened) ?? [])];
        return under.sort((a, b) => this._weightOf(b) - this._weightOf(a));
    }

    /**
     * The bands of the entered level, built once per level.
     * <p>
     * Without an opened genome the bands are the roots of the run. Opening one shows what is
     * inside it and nothing else: a band per child clade, plus one for the carriers of the genome
     * itself, which would otherwise vanish into its descendants. Going deeper is entering, not
     * unfolding — what lies outside the opened genome leaves the picture.
     * @private
     */
    _levelOf() {
        if (this._level) {
            return this._level;
        }
        const opened = this._path[this._path.length - 1] ?? null;
        const ranked = this._cladesUnder(opened);
        // A clade no sample ever saw gets no band of its own, however early in the list it sits
        const kept = ranked.slice(0, BANDS).filter(genome => this._weightOf(genome) > 0);

        const list = kept.map(founder => ({ founder, kind: 'clade', colour: null }));
        // One band for what is not a clade to open: the carriers of the opened genome itself and
        // the children too small to have been kept. The shares still add up to the population.
        list.push({
            founder: opened,
            kind: 'other',
            colour: null,
            absorbs: ranked.filter(genome => !kept.includes(genome))
        });

        const byFounder = new Map();
        for (const band of list) {
            if (band.founder !== null) {
                byFounder.set(band.founder, band);
            }
            for (const absorbed of band.absorbs ?? []) {
                byFounder.set(absorbed, band);
            }
        }
        this._level = { list, byFounder, opened, stacks: null };
        this._colour(list, kept.length);
        return this._level;
    }

    /**
     * Gives the clades of a level their colours.
     * <p>
     * The set is the curated one for their number, unchanged. What is chosen is which clade gets
     * which of them, and that follows time rather than size: clades are ordered by when their
     * population sat, and the set is then handed out in a spread order, so that two clades alive
     * at the same tick land far apart in the palette while two that succeed one another may share
     * a corner of it. A viewer looking at one tick sees colours it can tell apart, and a viewer
     * jumping to another tick sees every clade in the colour it had before.
     * @private
     */
    _colour(list, clades) {
        const colours = CladeModel._subsetFor(Math.max(1, clades));
        const bands = list.filter(band => band.kind === 'clade');

        // Where a clade's carriers sat on the timeline, as the mean sample weighted by them
        const stacks = this.stacks();
        const centre = new Map();
        for (const band of bands) {
            const at = list.indexOf(band);
            let carried = 0;
            let weighted = 0;
            stacks.forEach((stack, i) => {
                const share = stack.tops[at] - (at > 0 ? stack.tops[at - 1] : 0);
                carried += share;
                weighted += share * i;
            });
            centre.set(band, carried > 0 ? weighted / carried : 0);
        }

        const inTime = [...bands].sort((a, b) => centre.get(a) - centre.get(b));
        const half = Math.ceil(colours.length / 2);
        inTime.forEach((band, i) => {
            // 0, half, 1, half + 1, ... - neighbours in time land opposite each other
            const at = i % 2 === 0 ? i / 2 : half + (i - 1) / 2;
            band.colour = colours[at % colours.length];
        });
    }

    /** The colours for a level of this many clades. @private */
    static _subsetFor(count) {
        const subset = SUBSETS[count];
        if (subset) {
            return subset.map(i => PALETTE[i]);
        }
        return [...Array(count)].map((unused, i) => PALETTE[i % PALETTE.length]);
    }

    /** '#rrggbb' as a packed integer. @private */
    static _asInt(hex) {
        return parseInt(hex.slice(1), 16);
    }
}
