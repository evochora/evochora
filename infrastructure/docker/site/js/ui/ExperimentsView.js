/**
 * Draws the published experiments: one card per record as the model describes it, or the sentence
 * that stands in place of the cards where Zenodo gave none.
 *
 * @module ExperimentsView
 */

import { element, link } from './Element.js';

const COMMUNITY = 'https://zenodo.org/communities/evochora';
const PUBLISHED_EXPERIMENTS = 'https://github.com/evochora/evochora/blob/main/docs/PUBLISHED_EXPERIMENTS.md';

function card({ title, date, doi, doiUrl, clip }) {
    const parts = [];
    if (clip) {
        const video = element('video');
        video.controls = true;
        video.preload = 'metadata';
        video.src = clip;
        parts.push(video);
    }
    parts.push(element('div', {}, [
        element('div', { className: 'exp-title', text: title }),
        element('div', { className: 'exp-meta' }, [element('span', { text: date }), link(doiUrl, [doi], { newTab: true })])
    ]));
    return element('div', { className: 'exp' }, parts);
}

/**
 * @param {HTMLElement} box
 * @param {Array<object>|null} cards - The cards of the records, or null where there are none to
 *     show; in their place the sentence names where the experiments are published
 */
export function drawExperiments(box, cards) {
    if (!cards) {
        box.replaceChildren(element('p', { className: 'demo-none' }, [
            'The experiments are published in the ', link(COMMUNITY, ['Evochora community on Zenodo'], { newTab: true }),
            ' and described in ', link(PUBLISHED_EXPERIMENTS, ['Published Experiments'], { newTab: true }), '.'
        ]));
        return;
    }
    box.replaceChildren(...cards.map(card));
}
