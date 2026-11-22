import { describe, expect, test } from 'vitest';
import {
    NO_VALUE, activeRunIdOf, describeRuns, engineOf, factsOf, hasTicks, nodeStateOf, orderRunIds, paceText, recordedOf, startedAt, ticksText, toolLinks
} from '../../../../../main/web/root/js/model/Runs.js';

const RUN = '20261120-20342916-f039496a-f0ca-48c9-a876-627026137174';
const FORK = '20261120-20472152-99558c9b-575f-4f5b-8aca-3f04b14759ef';

/** The state of the pipeline as the node answers it. */
const pipeline = (status, engineState, activeRunId = null, metrics = {}) =>
    ({ status, activeRunId, services: [{ name: 'persistence-service-1', state: 'RUNNING' }, { name: 'simulation-engine', state: engineState, metrics }] });

/** The metadata of a run as the node answers it. */
const metadata = experiment => ({
    environment: { shape: [2048, 1152], topology: 'TORUS' },
    samplingInterval: 10000,
    resolvedConfigJson: JSON.stringify(experiment === undefined ? {} : { metadata: { experiment } })
});

describe('when a run was started', () => {
    test('is read from the date and time its ID begins with', () => {
        expect(startedAt(RUN)).toBe('2026-11-20 20:34');
    });

    test('is the ID itself where it does not begin with a date', () => {
        expect(startedAt('my-run')).toBe('my-run');
    });
});

describe('the facts of a run', () => {
    test('hold environment, topology and sampling', () => {
        expect(factsOf(metadata('A long run'))).toEqual({ name: 'A long run', size: '2048 × 1152', topology: 'TORUS', sampling: 10000 });
    });

    test('have no name for a run its configuration calls unnamed', () => {
        expect(factsOf(metadata('unnamed')).name).toBe('');
    });

    test('have no name for a run whose configuration names no experiment', () => {
        expect(factsOf(metadata()).name).toBe('');
    });

    test('have no name for a run whose configuration cannot be read', () => {
        expect(factsOf({ ...metadata('x'), resolvedConfigJson: '{' }).name).toBe('');
    });
});

describe('what a run has recorded', () => {
    test('a run without an answer or without a last tick has no ticks', () => {
        expect(hasTicks(null)).toBe(false);
        expect(hasTicks({ minTick: null, maxTick: null })).toBe(false);
        expect(hasTicks({ minTick: 0, maxTick: 0 })).toBe(true);
    });

    test('counts the organisms alive at the last tick and all that were born', () => {
        expect(recordedOf({ minTick: 0, maxTick: 2490000 }, { organisms: [{}, {}, {}], totalOrganismCount: 616 }))
            .toEqual({ minTick: 0, maxTick: 2490000, alive: 3, born: 616 });
    });

    test('knows no organisms where they could not be read', () => {
        expect(recordedOf({ minTick: 0, maxTick: 10 }, null)).toEqual({ minTick: 0, maxTick: 10, alive: null, born: null });
    });
});

describe('the run a node simulates', () => {
    test('is its active run while the pipeline runs or is degraded', () => {
        expect(activeRunIdOf(pipeline('RUNNING', 'RUNNING', RUN))).toBe(RUN);
        expect(activeRunIdOf(pipeline('DEGRADED', 'RUNNING', RUN))).toBe(RUN);
    });

    test('is its active run while the engine is paused, whatever the pipeline reports', () => {
        expect(activeRunIdOf(pipeline('STOPPED', 'PAUSED', RUN))).toBe(RUN);
    });

    test('is none on a node that only shows its runs', () => {
        expect(activeRunIdOf(pipeline('STOPPED', 'STOPPED', RUN))).toBeNull();
        expect(activeRunIdOf(pipeline('RUNNING', 'RUNNING'))).toBeNull();
    });

    test('has an empty engine where the pipeline lists none', () => {
        expect(engineOf({ status: 'STOPPED' })).toEqual({});
    });
});

describe('the state of the node', () => {
    test('is paused while the engine is paused, also in a degraded pipeline', () => {
        expect(nodeStateOf(pipeline('RUNNING', 'PAUSED', RUN), true)).toBe('paused');
        expect(nodeStateOf(pipeline('DEGRADED', 'PAUSED', RUN), true)).toBe('paused');
    });

    test('is degraded when the pipeline is', () => {
        expect(nodeStateOf(pipeline('DEGRADED', 'RUNNING', RUN), true)).toBe('degraded');
    });

    test('is none when the pipeline does not run', () => {
        expect(nodeStateOf(pipeline('STOPPED', 'STOPPED'), false)).toBe('none');
    });

    test('is starting until the run it simulates has recorded its first tick', () => {
        expect(nodeStateOf(pipeline('RUNNING', 'RUNNING', RUN), false)).toBe('starting');
        expect(nodeStateOf(pipeline('RUNNING', 'RUNNING', RUN), true)).toBe('running');
    });

    test('is running when the pipeline runs without naming a run', () => {
        expect(nodeStateOf(pipeline('RUNNING', 'RUNNING'), false)).toBe('running');
    });
});

describe('the order of the runs', () => {
    const listed = [{ runId: 'older', startTime: 1 }, { runId: 'newer', startTime: 2 }];

    test('is newest first and leaves the list it was given as it was', () => {
        expect(orderRunIds(listed, null)).toEqual(['newer', 'older']);
        expect(listed.map(run => run.runId)).toEqual(['older', 'newer']);
    });

    test('puts a run that simulates but is not indexed yet at the top', () => {
        expect(orderRunIds(listed, 'starting')).toEqual(['starting', 'newer', 'older']);
    });

    test('lists a run that simulates and is indexed once', () => {
        expect(orderRunIds(listed, 'older')).toEqual(['newer', 'older']);
    });
});

describe('the ticks of a run', () => {
    test('are its last tick when it starts at tick 0', () => {
        expect(ticksText({ minTick: 0, maxTick: 2490000 })).toBe('2,490,000');
    });

    test('are the stretch it holds when it is a fork', () => {
        expect(ticksText({ minTick: 1990000, maxTick: 1999999 })).toBe('1,990,000 – 1,999,999');
    });

    test('are unknown for a run that has recorded none', () => {
        expect(ticksText(undefined)).toBe(NO_VALUE);
    });
});

describe('the pace of the simulation', () => {
    const engine = { metrics: { ticks_per_second: 31872.6 } };

    test('is written in whole ticks per second while the node simulates', () => {
        expect(paceText('running', engine)).toBe('31,873 ticks per second');
        expect(paceText('degraded', engine)).toBe('31,873 ticks per second');
    });

    test('is not written while the node is paused, starting or idle, though the engine still reports a speed', () => {
        for (const state of ['paused', 'starting', 'none']) expect(paceText(state, engine)).toBe('');
    });

    test('is not written while the engine reports no speed', () => {
        expect(paceText('running', {})).toBe('');
    });
});

describe('the links to the tools', () => {
    test('open the run in the visualizer and in the analyzer', () => {
        expect(toolLinks(RUN)).toEqual([{ label: 'Visualizer', url: '/visualizer/?runId=' + RUN }, { label: 'Analyzer', url: '/analyzer/?runId=' + RUN }]);
    });

    test('pass on the tick and the organism the start page was opened with', () => {
        expect(toolLinks(RUN, { tick: '1990000', organism: '42' }).map(link => link.url))
            .toEqual(['/visualizer/?runId=' + RUN + '&tick=1990000&organism=42', '/analyzer/?runId=' + RUN + '&tick=1990000&organism=42']);
    });

    test('pass on nothing the address does not name', () => {
        expect(toolLinks(RUN, { tick: null, organism: '' })[0].url).toBe('/visualizer/?runId=' + RUN);
    });

    test('keep a run ID with characters an address cannot hold', () => {
        expect(toolLinks('a b&c')[0].url).toBe('/visualizer/?runId=a+b%26c');
    });
});

describe('the table of runs', () => {
    const facts = { [RUN]: factsOf(metadata('unnamed')), [FORK]: factsOf(metadata('unnamed')) };
    const recorded = {
        [RUN]: { minTick: 0, maxTick: 2490000, alive: 85, born: 616 },
        [FORK]: { minTick: 1990000, maxTick: 1999999, alive: 52, born: 484 }
    };
    const known = { runIds: [FORK, RUN], facts, recorded, activeRunId: null, state: 'none', togglePending: false };

    test('has no column for names while no run has one', () => {
        expect(describeRuns(known).named).toBe(false);
    });

    test('has a column for names as soon as one run has one', () => {
        const named = { ...facts, [FORK]: factsOf(metadata('A fork')) };
        const table = describeRuns({ ...known, facts: named });
        expect(table.named).toBe(true);
        expect(table.rows.map(row => row.name)).toEqual(['A fork', '']);
    });

    test('writes the cells of a run that is not simulated', () => {
        expect(describeRuns(known).rows[1]).toEqual({
            runId: RUN, started: '2026-11-20 20:34', live: false, state: null, mark: null, name: '',
            environment: { size: '2048 × 1152', topology: 'TORUS' }, sampling: '10,000', ticks: '2,490,000', alive: '85', born: '616', toggle: null,
            tools: [{ label: 'Visualizer', url: '/visualizer/?runId=' + RUN }, { label: 'Analyzer', url: '/analyzer/?runId=' + RUN }]
        });
    });

    test('writes a fork with the stretch it holds', () => {
        expect(describeRuns(known).rows[0].ticks).toBe('1,990,000 – 1,999,999');
    });

    test('marks the run the node simulates and offers to pause it', () => {
        const row = describeRuns({ ...known, activeRunId: RUN, state: 'running' }).rows[1];
        expect(row.live).toBe(true);
        expect(row.state).toBe('running');
        expect(row.mark).toEqual({ state: 'running', hint: '' });
        expect(row.toggle).toEqual({ action: 'pause', label: 'Pause', disabled: false });
    });

    test('offers to continue a paused run', () => {
        const row = describeRuns({ ...known, activeRunId: RUN, state: 'paused' }).rows[1];
        expect(row.toggle).toEqual({ action: 'resume', label: 'Continue', disabled: false });
    });

    test('explains the marks that do not explain themselves', () => {
        for (const state of ['starting', 'degraded']) {
            expect(describeRuns({ ...known, activeRunId: RUN, state }).rows[1].mark.hint).not.toBe('');
        }
    });

    test('offers nothing to press while a request awaits its effect', () => {
        const row = describeRuns({ ...known, activeRunId: RUN, state: 'running', togglePending: true }).rows[1];
        expect(row.toggle.disabled).toBe(true);
    });

    test('marks and offers nothing for the runs the node does not simulate', () => {
        const row = describeRuns({ ...known, activeRunId: RUN, state: 'running' }).rows[0];
        expect(row.live).toBe(false);
        expect(row.mark).toBeNull();
        expect(row.toggle).toBeNull();
    });

    test('writes a starting run, of which nothing is known yet', () => {
        const row = describeRuns({ runIds: ['starting'], facts: {}, recorded: {}, activeRunId: 'starting', state: 'starting', togglePending: false }).rows[0];
        expect(row).toMatchObject({ started: 'starting', name: '', environment: null, sampling: NO_VALUE, ticks: NO_VALUE, alive: NO_VALUE, born: NO_VALUE });
    });

    test('gives every row its links to the tools, with what the address of the page names', () => {
        const row = describeRuns({ ...known, carried: { tick: '5', organism: null } }).rows[0];
        expect(row.tools.map(link => link.url)).toEqual(['/visualizer/?runId=' + FORK + '&tick=5', '/analyzer/?runId=' + FORK + '&tick=5']);
    });

    test('writes a run whose organisms could not be read', () => {
        const table = describeRuns({ ...known, recorded: { ...recorded, [RUN]: { minTick: 0, maxTick: 10, alive: null, born: null } } });
        expect(table.rows[1]).toMatchObject({ ticks: '10', alive: NO_VALUE, born: NO_VALUE });
    });
});
