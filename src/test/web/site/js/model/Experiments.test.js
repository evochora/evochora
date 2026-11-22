import { describe, expect, test } from 'vitest';
import { clipUrl, describeExperiments } from '../../../../../../infrastructure/docker/site/js/model/Experiments.js';

/** A record of the community as Zenodo answers it. */
const record = (id, files) => ({
    id,
    doi: '10.5281/zenodo.' + id,
    doi_url: 'https://doi.org/10.5281/zenodo.' + id,
    metadata: { title: 'Children built far away', publication_date: '2026-11-02' },
    files
});

describe('the cards of the published experiments', () => {
    test('show title, date and DOI of each record, in the order Zenodo answers', () => {
        const cards = describeExperiments({ hits: { hits: [record(2, []), record(1, [])] } });
        expect(cards.map(card => card.doi)).toEqual(['10.5281/zenodo.2', '10.5281/zenodo.1']);
        expect(cards[0]).toEqual({ title: 'Children built far away', date: '2026-11-02', doi: '10.5281/zenodo.2', doiUrl: 'https://doi.org/10.5281/zenodo.2', clip: null });
    });

    test('play the first video of a record from Zenodo', () => {
        const cards = describeExperiments({ hits: { hits: [record(23897979, [{ key: 'analysis.ipynb' }, { key: 'dispersal clip.mp4' }, { key: 'second.mp4' }])] } });
        expect(cards[0].clip).toBe('https://zenodo.org/api/records/23897979/files/dispersal%20clip.mp4/content#t=0.5');
        expect(clipUrl(1, 'a.mp4')).toBe('https://zenodo.org/api/records/1/files/a.mp4/content#t=0.5');
    });

    test('have no clip for a record without a video, or without files', () => {
        expect(describeExperiments({ hits: { hits: [record(1, [{ key: 'data.tar' }])] } })[0].clip).toBeNull();
        expect(describeExperiments({ hits: { hits: [record(1, undefined)] } })[0].clip).toBeNull();
    });

    test('are none where Zenodo did not answer, answered without a list of records, or listed none', () => {
        expect(describeExperiments({ hits: { hits: [] } })).toBeNull();
        expect(describeExperiments(null)).toBeNull();
        expect(describeExperiments({ status: 500, message: 'Internal Server Error' })).toBeNull();
        expect(describeExperiments({ hits: {} })).toBeNull();
    });
});
