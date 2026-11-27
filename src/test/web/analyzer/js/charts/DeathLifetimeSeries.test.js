import { describe, expect, test } from 'vitest';
import { derive } from '../../../../../main/web/analyzer/js/charts/DeathLifetimeSeries.js';

const CONFIG = { deathsMetric: 'deaths', birthsMetric: 'births' };

/** The two tables as the card hands them over: one array of values per column. */
const tables = (deaths, births) => ({
    deaths: {
        organism_id: deaths.map(row => row[0]),
        death_tick: deaths.map(row => row[1])
    },
    births: {
        organism_id: births.map(row => row[0]),
        birth_tick: births.map(row => row[1])
    }
});

describe('the lifetimes of the organisms that died', () => {
    test('are measured from the birth in the births table to the death in the deaths table', () => {
        const rows = derive(tables([[7, 400]], [[7, 150]]), CONFIG, { from: 0, to: 1000 }, 2);

        expect(rows).toHaveLength(2);
        expect(rows[0]).toMatchObject({ tick: 250, deaths: 1, death_lifetime_p50: 250 });
    });

    test('are sorted into the window the organism died in', () => {
        const births = [[1, 0], [2, 0], [3, 0]];
        const rows = derive(tables([[1, 100], [2, 200], [3, 900]], births), CONFIG, { from: 0, to: 1000 }, 2);

        expect(rows.map(row => row.deaths)).toEqual([2, 1]);
        expect(rows[1].death_lifetime_p50).toBe(900);
    });

    test('give the percentiles of all the deaths of a window', () => {
        // Ten organisms born at tick 0 that died at 10, 20 ... 100, handed over out of order
        const ids = [5, 2, 9, 1, 7, 3, 10, 4, 8, 6];
        const births = ids.map(id => [id, 0]);
        const deaths = ids.map(id => [id, id * 10]);

        // The first of two windows reaches to tick 110 and holds all ten
        const [row] = derive(tables(deaths, births), CONFIG, { from: 0, to: 220 }, 2);

        expect(row).toMatchObject({
            deaths: 10,
            death_lifetime_p10: 20,
            death_lifetime_p25: 30,
            death_lifetime_p50: 60,
            death_lifetime_p75: 80,
            death_lifetime_p90: 100
        });
    });

    test('collapse onto one value where every organism died at the same age', () => {
        const births = [[1, 0], [2, 100], [3, 200]];
        const deaths = [[1, 48], [2, 148], [3, 248]];

        const [row] = derive(tables(deaths, births), CONFIG, { from: 0, to: 600 }, 2);

        expect(row.death_lifetime_p10).toBe(48);
        expect(row.death_lifetime_p90).toBe(48);
    });

    test('accept 64-bit columns, as a tick column arrives', () => {
        const companion = {
            deaths: { organism_id: [7], death_tick: [400n] },
            births: { organism_id: [7], birth_tick: [150n] }
        };

        expect(derive(companion, CONFIG, { from: 0, to: 1000 }, 2)[0].death_lifetime_p50).toBe(250);
    });
});

describe('a window without a death', () => {
    test('carries a count of zero and no percentile', () => {
        const rows = derive(tables([[1, 100]], [[1, 0]]), CONFIG, { from: 0, to: 1000 }, 2);

        expect(rows[1]).toMatchObject({ deaths: 0, death_lifetime_p10: null, death_lifetime_p50: null });
    });
});

describe('a death whose organism has no row in the births table', () => {
    test('counts as a death and enters no percentile', () => {
        const rows = derive(tables([[1, 100], [99, 120]], [[1, 0]]), CONFIG, { from: 0, to: 1000 }, 2);

        expect(rows[0].deaths).toBe(2);
        expect(rows[0].death_lifetime_p10).toBe(100);
        expect(rows[0].death_lifetime_p90).toBe(100);
    });

    test('leaves a window of only such deaths without a percentile', () => {
        const rows = derive(tables([[99, 120]], [[1, 0]]), CONFIG, { from: 0, to: 1000 }, 2);

        expect(rows[0]).toMatchObject({ deaths: 1, death_lifetime_p50: null });
    });
});

describe('the ticks the card covers', () => {
    test('are the window of the page, and deaths outside it are left out', () => {
        const births = [[1, 0], [2, 0], [3, 0]];
        const rows = derive(tables([[1, 50], [2, 500], [3, 950]], births), CONFIG, { from: 400, to: 600 }, 2);

        expect(rows.map(row => row.tick)).toEqual([450, 550]);
        expect(rows.map(row => row.deaths)).toEqual([0, 1]);
    });

    test('reach from the first to the last death where the page names no window', () => {
        const births = [[1, 0], [2, 0]];
        const rows = derive(tables([[1, 100], [2, 500]], births), CONFIG, null, 2);

        expect(rows.map(row => row.tick)).toEqual([200, 400]);
        expect(rows.map(row => row.deaths)).toEqual([1, 1]);
    });

    test('are cut into forty windows where the card names no number', () => {
        expect(derive(tables([[1, 100], [2, 500]], [[1, 0], [2, 0]]), CONFIG, null, null)).toHaveLength(40);
    });
});

describe('nothing to draw', () => {
    test('without the deaths table', () => {
        expect(derive({ births: { organism_id: [1], birth_tick: [0] } }, CONFIG, null, 2)).toEqual([]);
    });

    test('without the births table', () => {
        expect(derive({ deaths: { organism_id: [1], death_tick: [5] } }, CONFIG, null, 2)).toEqual([]);
    });

    test('without any death', () => {
        expect(derive(tables([], [[1, 0]]), CONFIG, null, 2)).toEqual([]);
    });

    test('without companions at all', () => {
        expect(derive(null, CONFIG, null, 2)).toEqual([]);
    });

    test('where every death lies at one tick and the page names no window', () => {
        expect(derive(tables([[1, 100], [2, 100]], [[1, 0], [2, 0]]), CONFIG, null, 2)).toEqual([]);
    });
});
