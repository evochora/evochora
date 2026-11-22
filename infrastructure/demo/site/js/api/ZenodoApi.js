/**
 * What the homepage asks of Zenodo: the records of the Evochora community, and the versions of
 * Evochora archived there.
 *
 * @module ZenodoApi
 */

import { readJson } from './Http.js';

const RECORDS = 'https://zenodo.org/api/records';

/** How many records of the community are asked for, the newest first */
const COMMUNITY_PAGE_SIZE = 20;

/**
 * @returns {Promise<{hits: {hits: Array<object>}}|null>} The published experiments of the community
 */
export const readCommunityRecords = () => readJson(RECORDS + '?communities=evochora&size=' + COMMUNITY_PAGE_SIZE + '&sort=newest');

/**
 * @param {string} conceptRecord - The number of the concept record that holds every version
 * @returns {Promise<{hits: {hits: Array<object>}}|null>} The records of all archived versions
 */
export const readVersionRecords = conceptRecord => readJson(RECORDS + '?q=conceptrecid:' + conceptRecord + '&all_versions=true');
