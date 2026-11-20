/**
 * Draws the runs of the node: the table the model describes, or the line that stands in its place
 * while there is nothing to list.
 *
 * @module RunTable
 */

import { NO_VALUE } from '../model/Runs.js';
import { copyButton } from './CopyButton.js';
import { anchor } from './DocumentationView.js';

/**
 * @param {string} tag
 * @param {{className?: string, title?: string, text?: string}} [properties]
 * @param {Array<Node|string>} [children]
 * @returns {HTMLElement}
 */
function element(tag, { className, title, text } = {}, children = []) {
    const node = document.createElement(tag);
    if (className !== undefined) node.className = className;
    if (title !== undefined) node.title = title;
    if (text !== undefined) node.textContent = text;
    node.append(...children);
    return node;
}

function headRow(named) {
    const sampling = element('th', { className: 'num', title: 'A run records every n-th tick', text: 'Sampling' });
    return element('tr', {}, [
        element('th', { className: named ? undefined : 'fill', text: 'Started' }),
        ...(named ? [element('th', { className: 'fill', text: 'Name' })] : []),
        element('th', { text: 'Environment' }),
        sampling,
        element('th', { className: 'num', text: 'Ticks' }),
        element('th', { className: 'num', text: 'Alive' }),
        element('th', { className: 'num', text: 'Born' }),
        element('th')
    ]);
}

function toggleButton(toggle, onToggle) {
    const button = element('button', { className: 'btn toggle', text: toggle.label });
    button.type = 'button';
    button.disabled = toggle.disabled;
    button.addEventListener('click', () => {
        button.disabled = true;
        onToggle(toggle.action);
    });
    return button;
}

function bodyRow(row, named, onToggle) {
    const started = element('td', { className: named ? undefined : 'fill' }, [row.started, copyButton(row.runId, 'run ID')]);
    if (row.mark) started.append(element('span', { className: 'chip ' + row.mark.state, title: row.mark.hint }, [element('i'), row.mark.state]));

    const environment = row.environment
        ? element('td', {}, [row.environment.size, element('span', { className: 'topology', text: row.environment.topology })])
        : element('td', { text: NO_VALUE });

    const actions = element('td');
    if (row.toggle) actions.append(toggleButton(row.toggle, onToggle), element('i', { className: 'rule' }));
    for (const tool of row.tools) {
        const link = element('a', { className: 'btn', text: tool.label });
        link.href = tool.url;
        actions.append(link);
    }

    return element('tr', { className: row.live ? 'live ' + row.state : '' }, [
        started,
        ...(named ? [element('td', { className: 'name fill', title: row.name, text: row.name })] : []),
        environment,
        element('td', { className: 'num', text: row.sampling }),
        element('td', { className: 'num', text: row.ticks }),
        element('td', { className: 'num', text: row.alive }),
        element('td', { className: 'num', text: row.born }),
        actions
    ]);
}

function content(view, onToggle) {
    if (view.kind === 'unreachable') return element('p', { className: 'none error', text: 'This node is not reachable.' });
    if (view.kind === 'empty') {
        return element('p', { className: 'none' }, [
            'No simulation runs on this node yet.', element('br'), 'Start one with ', element('code', { text: 'bin/evochora node run' }),
            ', as ', anchor(view.guide), ' in the User Guide describes.'
        ]);
    }
    return element('table', {}, [
        element('thead', {}, [headRow(view.named)]),
        element('tbody', {}, view.rows.map(row => bodyRow(row, view.named, onToggle)))
    ]);
}

/**
 * Creates the function that draws the runs into a panel. It draws only what has changed since it
 * last drew: an unchanged list keeps its scroll position and an open tooltip.
 *
 * @param {HTMLElement} panel
 * @param {function('pause'|'resume'): void} onToggle - Called when the button of the simulated
 *     run is pressed
 * @returns {function(object): void} Takes what to show: {kind: 'unreachable'}, {kind: 'empty',
 *     guide} with the link to the chapter on starting a run, or {kind: 'table', named, rows} as
 *     the model describes the table
 */
export function runTableIn(panel, onToggle) {
    let drawn = null;
    return view => {
        const signature = JSON.stringify(view);
        if (signature === drawn) return;
        drawn = signature;
        panel.replaceChildren(content(view, onToggle));
    };
}
