/**
 * Draws the download as the model describes it: the button, the version beside the further
 * archives, and the DOIs under which Evochora is archived.
 *
 * @module DownloadView
 */

import { element, link } from './Element.js';

/**
 * @param {{button: HTMLAnchorElement, notice: HTMLElement, noticeSeparator: HTMLElement, version: HTMLElement,
 *     separator: HTMLElement, others: HTMLElement}} places
 * @param {{button: {url: string, label: string, newTab: boolean}, version: string,
 *     others: null|{label: string, links: Array<{label: string, url: string}>}, notice?: string}} download
 */
export function drawDownload(places, download) {
    const { button, notice, noticeSeparator, version, separator, others } = places;
    if (download.notice) {
        notice.textContent = download.notice;
        notice.hidden = false;
        noticeSeparator.hidden = false;
    }
    button.href = download.button.url;
    button.textContent = download.button.label;
    if (!download.button.newTab) button.removeAttribute('target');
    version.textContent = download.version;
    version.hidden = false;
    separator.hidden = false;
    if (download.others) {
        others.replaceChildren(
            element('span', { text: download.others.label }),
            ...download.others.links.map(archive => link(archive.url, [archive.label])),
            element('i', { className: 'sep' })
        );
    }
}

/**
 * @param {HTMLElement} place - The place beside the version
 * @param {{doi: string, url: string}} doi - The DOI of the version offered
 */
export function drawVersionDoi(place, doi) {
    place.replaceChildren('DOI ', link(doi.url, [doi.doi], { newTab: true }));
    place.hidden = false;
}

/**
 * @param {HTMLElement} place - The entry in the list of how to cite Evochora
 * @param {{label: string, url: string}} doi - The DOI of Evochora across all its versions
 */
export function drawConceptDoi(place, doi) {
    place.replaceChildren(link(doi.url, [doi.label], { newTab: true }), element('span', { text: 'Evochora as a whole, across all its versions.' }));
    place.hidden = false;
}
