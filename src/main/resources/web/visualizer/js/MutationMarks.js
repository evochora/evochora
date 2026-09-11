/**
 * The rules that decide which cells of the environment grid carry the mark of a mutation.
 *
 * The mutations of a lineage are fetched once for the selected organism, while the molecules they
 * are compared against change with every tick and every viewport move. The work is therefore split
 * in two: {@link buildMarkMap} turns the answer into a map from cell key to the one event that
 * decides that cell, which depends on the answer alone, and {@link isMarkPresent} answers for one
 * cell whether the mark is there, which depends on the molecule the cell holds at the moment it is
 * drawn.
 *
 * This module knows neither the DOM nor the renderer. It receives the molecule of a cell as the
 * fields the grid already holds and gives back plain objects.
 *
 * @module MutationMarks
 */

/**
 * The packed molecule type of a CODE molecule, which is what an empty cell holds.
 *
 * The mutations endpoint reports a molecule as the type constant at its place in the packed
 * molecule together with the signed value; CODE sits at zero, so a written molecule of type zero
 * and value zero is what a deletion leaves behind.
 */
const PACKED_TYPE_CODE = 0;

/**
 * Builds the map from cell key to the mutation that decides the cell's mark.
 *
 * <p><strong>Rule 2.</strong> Where two events of the lineage touch the same cell, the younger one
 * decides: the event of the more recent generation, and within one birth the event with the larger
 * event index. That comparison depends on the events alone, never on what the cell holds, which is
 * why it is settled here and once.
 *
 * An event without cells — the label mask is one — changed no molecule and produces no mark.
 *
 * @param {Array<object>} events The events of the lineage as the mutations endpoint reports them.
 * @param {object} options Options.
 * @param {function(number): (string|null)} options.resolveTypeName Maps a packed molecule type to the
 *                                           type id the grid holds in its cell data, or null for a
 *                                           type this run does not name.
 * @returns {Map<string, object>} Cell key `"x,y"` to the deciding mark.
 */
export function buildMarkMap(events, { resolveTypeName }) {
    const marks = new Map();
    if (!Array.isArray(events) || events.length === 0) {
        return marks;
    }

    for (const event of events) {
        if (!Array.isArray(event.cells)) {
            continue;
        }
        for (const cell of event.cells) {
            // The answer carries one coordinate per dimension of the world; the grid is the
            // two-dimensional party and draws the first two, as it does for every cell.
            const coordinates = cell.coordinates;
            if (!Array.isArray(coordinates) || coordinates.length < 2) {
                continue;
            }
            const candidate = toMark(event, cell, coordinates, resolveTypeName);
            const key = `${candidate.x},${candidate.y}`;
            const standing = marks.get(key);
            if (!standing || isYounger(candidate, standing)) {
                marks.set(key, candidate);
            }
        }
    }
    return marks;
}

/**
 * Decides whether a cell carries its mark.
 *
 * <p><strong>Rule 1.</strong> A cell is marked only while it still holds what the mutation wrote:
 * its molecule equals the event's new value. A cell the organism itself has since overwritten with
 * a different molecule carries no mark. A deletion's new value is empty, so the cleared cell is
 * marked while it stays empty, wherever the offset lands, inside or outside the displayed body:
 * the mark shows where the deletion happened, and a restriction to the body would hide that.
 * Marker bits are not compared; they belong to the organism, not to the molecule.
 *
 * A cell the loaded region does not name is an empty cell and is passed as a type id of null.
 *
 * @param {object} mark The deciding mark of that cell, from {@link buildMarkMap}.
 * @param {string|null} typeName The type name of the cell's molecule, null when the cell holds nothing.
 * @param {number} value The value of the cell's molecule.
 * @param {boolean} isEmptyCell Whether the cell is empty, as the renderers decide it.
 * @returns {boolean} True while the cell still holds what the mutation wrote.
 */
export function isMarkPresent(mark, typeName, value, isEmptyCell) {
    if (mark.afterEmpty) {
        return isEmptyCell;
    }
    if (typeName === null) {
        return false;
    }
    return typeName === mark.afterTypeName && value === mark.afterValue;
}

/**
 * Builds one mark from one cell of one event.
 *
 * @param {object} event The event the cell belongs to.
 * @param {object} cell The cell with the molecules before and after the write.
 * @param {number[]} coordinates Its absolute coordinates on the displayed body.
 * @param {function(number): (string|null)} resolveTypeName Maps a packed molecule type to its name.
 * @returns {object} The mark of that cell.
 */
function toMark(event, cell, coordinates, resolveTypeName) {
    const before = cell.before || {};
    const after = cell.after || {};
    return {
        x: coordinates[0],
        y: coordinates[1],
        kind: event.kind,
        generation: event.originGeneration ?? 0,
        eventIndex: event.eventIndex ?? 0,
        originOrganismId: event.originOrganismId,
        genomeHash: event.originGenomeHash,
        beforeTypeName: resolveTypeName(before.moleculeType),
        beforeValue: before.moleculeValue ?? 0,
        afterTypeName: resolveTypeName(after.moleculeType),
        afterValue: after.moleculeValue ?? 0,
        afterEmpty: after.moleculeType === PACKED_TYPE_CODE && after.moleculeValue === 0
    };
}

/**
 * Compares two marks of the same cell by rule 2.
 *
 * @param {object} candidate The mark offered for the cell.
 * @param {object} standing The mark that holds the cell so far.
 * @returns {boolean} True if the candidate arose in a younger birth, or later within the same one.
 */
function isYounger(candidate, standing) {
    if (candidate.generation !== standing.generation) {
        return candidate.generation > standing.generation;
    }
    return candidate.eventIndex > standing.eventIndex;
}

