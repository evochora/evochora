/**
 * The cards of the published experiments, worked out from the records of the Evochora community on
 * Zenodo.
 *
 * @module Experiments
 */

const RECORDS = 'https://zenodo.org/api/records/';

/** The moment a clip is loaded at, so that its player shows a frame of it before it is played */
const POSTER_FRAGMENT = '#t=0.5';

/**
 * Where a clip of a record is played from.
 *
 * @param {number|string} recordId
 * @param {string} key - The name of the file in the record
 * @returns {string}
 */
export function clipUrl(recordId, key) {
    return RECORDS + recordId + '/files/' + encodeURIComponent(key) + '/content' + POSTER_FRAGMENT;
}

/**
 * Describes the cards of the experiments.
 *
 * @param {{hits?: {hits?: Array<object>}}|null} answer - What Zenodo answers for the community, or
 *     null where it did not answer
 * @returns {Array<{title: string, date: string, doi: string, doiUrl: string, clip: string|null}>|null}
 *     One card per record, the clip null for a record without a video; null where there is no card
 *     to show: Zenodo did not answer, answered without a list of records, or listed none
 */
export function describeExperiments(answer) {
    if (!answer || !answer.hits || !Array.isArray(answer.hits.hits) || !answer.hits.hits.length) return null;
    return answer.hits.hits.map(record => {
        const clip = (record.files || []).find(file => /\.mp4$/.test(file.key));
        return {
            title: record.metadata.title,
            date: record.metadata.publication_date,
            doi: record.doi,
            doiUrl: record.doi_url,
            clip: clip ? clipUrl(record.id, clip.key) : null
        };
    });
}
