/**
 * Genome Depth Series
 *
 * Derives how many genome changes separate the living genomes from their founders, as two series
 * over time: the deepest living genome and the average depth over the organisms carrying one.
 *
 * It reads two companion tables whose metric ids the visualization config names: the genome
 * population under `populationMetric`, rows of `(tick, genome_hash, count)`, and the genome lineage
 * under `lineageMetric`, rows of `(genome_hash, parent_genome_hash, first_birth_tick)`. Genome
 * hashes arrive as text, because 64 bits do not survive a JavaScript number.
 *
 * A genome without a lineage edge is a founding genome and has depth 0. Every edge is one genome
 * change, including a change from or to the empty genome (hash 0), since the edge exists only
 * where child and parent differ. A genome that arose more than once is placed under its earliest
 * edge, as the clade chart does.
 *
 * @module GenomeDepthSeries
 */

/**
 * Picks one parent per genome, the one of its earliest edge.
 *
 * @param {Array<Object>} lineageRows - Rows with genome_hash, parent_genome_hash, first_birth_tick
 * @returns {Map<string, string>} Genome to its parent; founding genomes are absent
 */
function parentsOf(lineageRows) {
    const parents = new Map();
    const since = new Map();
    for (const row of lineageRows) {
        const genome = row.genome_hash;
        const parent = row.parent_genome_hash;
        if (genome == null || parent == null) continue;
        const birth = Number(row.first_birth_tick ?? 0);
        if (parents.has(genome) && since.get(genome) <= birth) continue;
        parents.set(genome, parent);
        since.set(genome, birth);
    }
    return parents;
}

/**
 * Counts the edges between a genome and its founding genome, remembering every depth found on the
 * way. A lineage that loops back on itself - possible only through repeated hashes - is cut where
 * it would revisit a genome.
 *
 * @param {string} genome - Genome to place
 * @param {Map<string, string>} parents - Genome to its parent
 * @param {Map<string, number>} depths - Depths found so far, extended in place
 * @returns {number} The genome's depth
 */
function depthOf(genome, parents, depths) {
    const path = [];
    const onPath = new Set();
    let current = genome;
    while (!depths.has(current) && parents.has(current) && !onPath.has(current)) {
        path.push(current);
        onPath.add(current);
        current = parents.get(current);
    }
    let depth = depths.get(current) ?? 0;
    if (!depths.has(current)) {
        depths.set(current, depth);
    }
    for (let i = path.length - 1; i >= 0; i--) {
        depth += 1;
        depths.set(path[i], depth);
    }
    return depths.get(genome);
}

/**
 * Derives the genome depth series for the given ticks.
 *
 * @param {Array<number>} ticks - Ticks the chart shows, in order
 * @param {Object<string, Array<Object>>|null} companion - Rows per companion metric id
 * @param {Object} config - Visualization config naming the companion metrics
 * @returns {Array<{key: string, label: string, values: Array<number|null>}>} The two series, or
 *          an empty list when the companions are missing
 */
export function derive(ticks, companion, config) {
    const population = companion?.[config.populationMetric];
    const lineage = companion?.[config.lineageMetric];
    if (!population || !lineage) {
        return [];
    }

    const parents = parentsOf(lineage);
    const depths = new Map();
    const perTick = new Map();
    for (const row of population) {
        const count = Number(row.count ?? 0);
        if (count <= 0 || row.genome_hash == null) continue;
        const tick = Number(row.tick);
        const depth = depthOf(row.genome_hash, parents, depths);
        let entry = perTick.get(tick);
        if (!entry) {
            entry = { max: 0, weighted: 0, carriers: 0 };
            perTick.set(tick, entry);
        }
        entry.max = Math.max(entry.max, depth);
        entry.weighted += depth * count;
        entry.carriers += count;
    }

    const at = tick => perTick.get(Number(tick)) || null;
    return [
        {
            key: 'max_genome_depth',
            label: 'Max Genome Depth',
            values: ticks.map(tick => at(tick)?.max ?? null)
        },
        {
            key: 'avg_genome_depth',
            label: 'Avg Genome Depth',
            values: ticks.map(tick => {
                const entry = at(tick);
                return entry && entry.carriers > 0 ? entry.weighted / entry.carriers : null;
            })
        }
    ];
}
