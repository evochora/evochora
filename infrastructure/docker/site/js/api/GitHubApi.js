/**
 * What the homepage asks of GitHub: the latest published release of Evochora.
 *
 * @module GitHubApi
 */

import { readJson } from './Http.js';

/**
 * @returns {Promise<{tag_name: string, html_url: string, assets: Array<{name: string, size: number,
 *     browser_download_url: string}>}|null>} The release with its archives, or null where GitHub
 *     does not answer or refuses, as it does once its limit of requests is reached
 */
export const readLatestRelease = () => readJson('https://api.github.com/repos/evochora/evochora/releases/latest');
