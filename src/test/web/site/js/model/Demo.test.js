import { describe, expect, test } from 'vitest';
import { describeEntry, hasTicks, namingOf, orderRunIds, samplingText, startedOn } from '../../../../../../infrastructure/demo/site/js/model/Demo.js';

const RUN = '20261120-20342916-f039496a-f0ca-48c9-a876-627026137174';

/** The metadata of a run as the node answers it. */
const metadata = (experiment, changes = {}) => ({
    environment: { shape: [2048, 1152], topology: 'TORUS' },
    samplingInterval: 10000,
    resolvedConfigJson: JSON.stringify(experiment === undefined ? {} : { metadata: { experiment } }),
    ...changes
});

const plain = text => ({ text, strong: false });
const strong = text => ({ text, strong: true });

describe('the runs of the demo', () => {
    test('are listed the newest first', () => {
        expect(orderRunIds([{ runId: 'a', startTime: 1 }, { runId: 'c', startTime: 3 }, { runId: 'b', startTime: 2 }])).toEqual(['c', 'b', 'a']);
    });

    test('leave the list the node answered as it is', () => {
        const listed = [{ runId: 'a', startTime: 1 }, { runId: 'b', startTime: 2 }];
        orderRunIds(listed);
        expect(listed.map(run => run.runId)).toEqual(['a', 'b']);
    });
});

describe('the ticks of a run', () => {
    test('are none without an answer or without a last tick', () => {
        expect(hasTicks(null)).toBe(false);
        expect(hasTicks({ minTick: null, maxTick: null })).toBe(false);
        expect(hasTicks({})).toBe(false);
        expect(hasTicks({ minTick: 0, maxTick: 0 })).toBe(true);
    });
});

describe('the title and the description of a run', () => {
    test('are the first line of its experiment name and the rest', () => {
        expect(namingOf(JSON.stringify({ metadata: { experiment: ' A long run \n A large population.\nSecond line. ' } })))
            .toEqual({ name: 'A long run', description: 'A large population.\nSecond line.' });
    });

    test('are a title alone for a name of one line', () => {
        expect(namingOf(JSON.stringify({ metadata: { experiment: 'Every tick of a sweep' } }))).toEqual({ name: 'Every tick of a sweep', description: '' });
    });

    test('are empty for a run its configuration calls unnamed, names nothing, or cannot be read', () => {
        const none = { name: '', description: '' };
        expect(namingOf(JSON.stringify({ metadata: { experiment: 'unnamed' } }))).toEqual(none);
        expect(namingOf(JSON.stringify({}))).toEqual(none);
        expect(namingOf('{')).toEqual(none);
    });

    test('are kept as text, markup included', () => {
        expect(namingOf(JSON.stringify({ metadata: { experiment: 'Energy < entropy & more\nA <b>description</b>' } })))
            .toEqual({ name: 'Energy < entropy & more', description: 'A <b>description</b>' });
    });
});

describe('the sampling of a run', () => {
    test('says every tick where every tick was recorded', () => {
        expect(samplingText(1)).toBe('every tick recorded');
    });

    test('names the interval in groups of three', () => {
        expect(samplingText(10000)).toBe('every 10,000th tick recorded');
    });
});

describe('the entry of a run', () => {
    const ticks = { minTick: 0, maxTick: 2490000 };
    const organisms = { organisms: [], totalOrganismCount: 61600 };

    test('names the run, its environment, its ticks, its sampling and its births', () => {
        expect(describeEntry(RUN, metadata('A long run\nA large population.'), ticks, organisms)).toEqual({
            title: 'A long run',
            description: 'A large population.',
            facts: [
                [strong('2048 × 1152'), plain(' TORUS')],
                [strong('2,490,000'), plain(' ticks')],
                [plain('every 10,000th tick recorded')],
                [strong('61,600'), plain(' organisms born')]
            ],
            links: [
                { label: 'Open in Visualizer', url: '/visualizer/?runId=' + RUN },
                { label: 'Open in Analyzer', url: '/analyzer/?runId=' + RUN }
            ]
        });
    });

    test('is titled by the day it was started where the run has no name', () => {
        expect(describeEntry(RUN, metadata(), ticks, organisms).title).toBe('Run of 2026-11-20');
        expect(describeEntry(RUN, metadata('unnamed'), ticks, organisms).title).toBe('Run of 2026-11-20');
        expect(startedOn('my-run')).toBe('my-run');
    });

    test('names a fork by the stretch of its parent run it holds', () => {
        expect(describeEntry(RUN, metadata(), { minTick: 1000000, maxTick: 1200000 }, organisms).facts[1])
            .toEqual([plain('ticks '), strong('1,000,000 – 1,200,000')]);
    });

    test('of a run without metadata has a title, its ticks and births, and nothing of the metadata', () => {
        const entry = describeEntry(RUN, null, ticks, organisms);
        expect(entry.title).toBe('Run of 2026-11-20');
        expect(entry.description).toBe('');
        expect(entry.facts).toEqual([[strong('2,490,000'), plain(' ticks')], [strong('61,600'), plain(' organisms born')]]);
    });

    test('of a run without ticks names neither ticks nor births', () => {
        for (const range of [null, { minTick: null, maxTick: null }]) {
            expect(describeEntry(RUN, metadata(), range, organisms).facts).toEqual([[strong('2048 × 1152'), plain(' TORUS')], [plain('every 10,000th tick recorded')]]);
        }
    });

    test('names no births where the organisms of the last tick could not be read', () => {
        expect(describeEntry(RUN, metadata(), ticks, null).facts).toHaveLength(3);
    });

    test('names an environment without a topology by its size alone', () => {
        expect(describeEntry(RUN, metadata(undefined, { environment: { shape: [100, 100], topology: '' } }), null).facts[0]).toEqual([strong('100 × 100')]);
    });

    test('names no sampling where the metadata gives none', () => {
        expect(describeEntry(RUN, metadata(undefined, { samplingInterval: 0 }), null).facts).toEqual([[strong('2048 × 1152'), plain(' TORUS')]]);
    });

    test('opens the run by its encoded ID', () => {
        expect(describeEntry('a b&c', null, null).links[0].url).toBe('/visualizer/?runId=a%20b%26c');
    });
});
