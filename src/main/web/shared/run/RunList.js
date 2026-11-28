/**
 * What a list of runs needs to be shown and walked through: its order, the runs a typed text
 * leaves, how a run ID reads, and which entry the arrow keys reach next.
 *
 * The functions here touch no page, so that the run switcher of every interface shows the same
 * list the same way.
 *
 * @module RunList
 */

/**
 * Shortens a run ID that follows no known pattern.
 *
 * @param {string} id - The run ID
 * @returns {string} Its first and last characters, or the whole ID where it is short
 */
function shorten(id) {
    const value = id || '';
    return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-4)}` : value;
}

/**
 * Formats a run ID for display: the date and time it carries, and the end of its UUID.
 *
 * @param {string} runId - The run ID
 * @returns {string} The text shown for the run
 */
export function formatRunId(runId) {
    if (!runId) return '(none)';
    // Pattern: YYYYMMDD-HHMMSSxx-uuid
    const match = runId.match(/^(\d{8})-(\d{8})-([0-9a-fA-F-]+)$/);
    if (match) {
        const dateDigits = match[1];
        const timeDigits = match[2];
        const uuid = match[3];
        const yyyy = dateDigits.slice(0, 4);
        const mm = dateDigits.slice(4, 6);
        const dd = dateDigits.slice(6, 8);
        const hh = timeDigits.slice(0, 2);
        const mi = timeDigits.slice(2, 4);
        const ss = timeDigits.slice(4, 6);
        const last4 = uuid.replace(/-/g, '').slice(-4);
        return `${yyyy}-${mm}-${dd}/${hh}:${mi}:${ss}-${last4}`;
    }
    // Fallback: try generic date/time with separators
    const sepMatch = runId.match(/(\d{4}-\d{2}-\d{2})[T_\- ]?(?:(\d{2})[:\-_]?(\d{2})[:\-_]?(\d{2}))?/);
    if (sepMatch) {
        const date = sepMatch[1];
        const hh = sepMatch[2] || '00';
        const mi = sepMatch[3] || '00';
        const ss = sepMatch[4] || '00';
        const last4 = runId.replace(/-/g, '').slice(-4);
        return `${date}/${hh}:${mi}:${ss}-${last4}`;
    }
    return shorten(runId);
}

/**
 * Sorts runs by their start, the newest first. A run without a start time is placed by the date
 * and time its ID begins with.
 *
 * @param {Array<{runId: string, startTime?: number}>} runs - The runs
 * @returns {Array<{runId: string, startTime?: number}>} The runs in a new array, newest first
 */
export function sortRuns(runs) {
    const score = (run) => {
        if (run.startTime) return run.startTime;
        const match = (run.runId || '').match(/^(\d{8})-(\d{8})/);
        return match ? Number(match[1] + match[2]) : 0;
    };
    return [...runs].sort((a, b) => score(b) - score(a));
}

/**
 * Keeps the runs whose ID contains the typed text, whatever its case.
 *
 * @param {Array<{runId: string}>} runs - The runs
 * @param {string} text - What was typed into the filter
 * @returns {Array<{runId: string}>} The runs the text leaves
 */
export function filterRuns(runs, text) {
    const term = (text || '').toLowerCase();
    return runs.filter(run => run.runId && run.runId.toLowerCase().includes(term));
}

/**
 * The entry an arrow key reaches from the marked one. The list is walked in a circle: past its end
 * comes its first entry, before its first its last.
 *
 * @param {number} marked - Index of the marked entry, or -1 where none is marked
 * @param {number} delta - +1 for the next entry, -1 for the one before
 * @param {number} count - How many entries the list holds
 * @returns {number} Index of the entry reached, or -1 for an empty list
 */
export function stepIndex(marked, delta, count) {
    if (count === 0) return -1;
    const next = marked + delta;
    if (next < 0) return count - 1;
    if (next >= count) return 0;
    return next;
}
