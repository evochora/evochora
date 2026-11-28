import { describe, expect, test } from 'vitest';
import { filterRuns, formatRunId, sortRuns, stepIndex } from '../../../../main/web/shared/run/RunList.js';

const RUN = '20261125-03243700-9efb39ab-290a-4398-8c50-fce57195ae42';

describe('a run ID on display', () => {
    test('reads as its date, its time and the end of its UUID', () => {
        expect(formatRunId(RUN)).toBe('2026-11-25/03:24:37-ae42');
    });

    test('reads as a date and time where it carries them with separators', () => {
        expect(formatRunId('run-2026-11-25_13-57-01-abcd')).toBe('2026-11-25/13:57:01-abcd');
    });

    test('is shortened where it follows no pattern', () => {
        expect(formatRunId('an-id-that-names-no-date-at-all')).toBe('an-id-th…-all');
        expect(formatRunId('short')).toBe('short');
    });

    test('says so where there is no run', () => {
        expect(formatRunId(null)).toBe('(none)');
    });
});

describe('the order of the runs', () => {
    test('is the newest first, by start time', () => {
        const sorted = sortRuns([{ runId: 'a', startTime: 1 }, { runId: 'c', startTime: 3 }, { runId: 'b', startTime: 2 }]);
        expect(sorted.map(run => run.runId)).toEqual(['c', 'b', 'a']);
    });

    test('takes the date and time of the ID where a run names no start time', () => {
        const sorted = sortRuns([
            { runId: '20261125-03243700-aaaa' },
            { runId: '20261125-13570148-bbbb' }
        ]);
        expect(sorted.map(run => run.runId)).toEqual(['20261125-13570148-bbbb', '20261125-03243700-aaaa']);
    });

    test('leaves the list it was given as it is', () => {
        const runs = [{ runId: 'a', startTime: 1 }, { runId: 'b', startTime: 2 }];
        sortRuns(runs);
        expect(runs.map(run => run.runId)).toEqual(['a', 'b']);
    });
});

describe('the runs a typed text leaves', () => {
    const runs = [{ runId: '20261125-ABCD' }, { runId: '20261126-efgh' }, { runId: '' }];

    test('are those whose ID contains it, whatever its case', () => {
        expect(filterRuns(runs, 'abcd').map(run => run.runId)).toEqual(['20261125-ABCD']);
        expect(filterRuns(runs, 'EFG').map(run => run.runId)).toEqual(['20261126-efgh']);
    });

    test('are all runs with an ID where nothing is typed', () => {
        expect(filterRuns(runs, '')).toHaveLength(2);
    });
});

describe('the entry an arrow key reaches', () => {
    test('is the first from no marked entry going down, the last going up', () => {
        expect(stepIndex(-1, 1, 3)).toBe(0);
        expect(stepIndex(-1, -1, 3)).toBe(2);
    });

    test('is the neighbour of the marked one', () => {
        expect(stepIndex(0, 1, 3)).toBe(1);
        expect(stepIndex(2, -1, 3)).toBe(1);
    });

    test('goes round past either end', () => {
        expect(stepIndex(2, 1, 3)).toBe(0);
        expect(stepIndex(0, -1, 3)).toBe(2);
    });

    test('is none in an empty list', () => {
        expect(stepIndex(-1, 1, 0)).toBe(-1);
    });
});
