/**
 * Draws the Live Demo: one entry per run as the model describes it, or the line that stands in
 * place of the entries while the node has no run.
 *
 * @module DemoView
 */

import { element, link } from './Element.js';

function fact(parts) {
    return element('span', {}, parts.map(part => part.strong ? element('b', { text: part.text }) : part.text));
}

function entry({ title, description, facts, links }) {
    const text = element('div', {}, [element('div', { className: 'demo-name', text: title })]);
    if (description) text.append(element('p', { className: 'demo-desc', text: description }));
    text.append(element('div', { className: 'demo-facts' }, facts.map(fact)));
    const open = element('div', { className: 'demo-open' }, links.map(target => link(target.url, [target.label], { className: 'btn-secondary' })));
    return element('div', { className: 'demo-run' }, [text, open]);
}

/**
 * @param {HTMLElement} box
 * @param {Array<object>|null} entries - The entries of the runs, or null where the node lists none
 */
export function drawDemo(box, entries) {
    if (!entries || !entries.length) {
        box.replaceChildren(element('p', { className: 'demo-none', text: 'No simulation runs are available at the moment.' }));
        return;
    }
    box.replaceChildren(...entries.map(entry));
}
