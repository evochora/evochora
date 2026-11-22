/**
 * Draws the documents of the start page: the topics with their texts, the groups of links in the
 * foot, and the build of the node.
 *
 * @module DocumentationView
 */

import { copyButton } from './CopyButton.js';

/**
 * @param {{label: string, url: string, external: boolean}} link
 * @returns {HTMLAnchorElement} A link that leaves the node opens in a tab of its own
 */
export function anchor(link) {
    const a = document.createElement('a');
    a.href = link.url;
    if (link.external) {
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
    }
    a.textContent = link.label;
    return a;
}

function heading(text) {
    const h3 = document.createElement('h3');
    h3.textContent = text;
    return h3;
}

function topic({ label, text, emphasis, links }) {
    const box = document.createElement('div');
    box.className = emphasis ? 'topic start' : 'topic';
    const paragraph = document.createElement('p');
    paragraph.textContent = text;
    const list = document.createElement('ul');
    list.className = 'links';
    for (const link of links) {
        const item = document.createElement('li');
        item.append(anchor(link));
        list.append(item);
    }
    box.append(heading(label), paragraph, list);
    return box;
}

function group({ label, links }) {
    const box = document.createElement('div');
    box.append(heading(label), ...links.map(anchor));
    return box;
}

function buildLine(build) {
    const line = document.createElement('div');
    line.className = 'version';
    if (build.release) {
        line.textContent = 'Evochora ' + build.release;
    } else {
        line.title = build.title;
        line.append('revision ' + build.shortRevision, copyButton(build.revision, 'revision'));
    }
    return line;
}

/**
 * @param {{work: HTMLElement, more: HTMLElement}} places - Where the topics and where the groups
 *     of the foot are drawn
 * @param {{work: Array, more: Array, project: Array}} documentation - The documents with their
 *     links resolved
 * @param {object|null} build - The build of the node as the model describes it, or null while it
 *     is not known
 */
export function drawDocumentation(places, documentation, build) {
    places.work.replaceChildren(...documentation.work.map(topic));

    // The links to the project stand as a group without a heading, level with the links of the others
    const project = document.createElement('div');
    project.className = 'plain';
    project.append(heading(' '), ...documentation.project.map(anchor));
    if (build) project.append(buildLine(build));
    places.more.replaceChildren(...documentation.more.map(group), project);
}
