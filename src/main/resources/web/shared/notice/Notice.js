/**
 * Page-level notices shared by the visualizer and the analyzer.
 *
 * One notice is shown at a time, centred over the page: either an error card or the start card
 * of a run whose first data is on its way. Showing a notice replaces the one shown before.
 * The stylesheet `notice.css` next to this module has to be linked by the page.
 */

/** Share of the progress bar the first chunk fills; the rest stands for indexing. */
const SIMULATION_SHARE = 0.8;

let scrim = null;
let current = null;

/**
 * Shows an error card.
 *
 * A closable card gets a Close button and closes on Escape; it dims the page only lightly, as
 * the page stays usable. A card that is not closable stays until the page leaves it.
 *
 * @param {object} options
 * @param {string} options.title
 * @param {string} [options.text] - Explanation for the user.
 * @param {string} [options.detail] - Technical cause, shown set apart.
 * @param {Array<{label: string, onClick: function(): void, primary?: boolean}>} [options.actions]
 * @param {boolean} [options.closable=false]
 */
export function showErrorNotice({ title, text, detail, actions = [], closable = false }) {
    const card = element('div', 'notice notice-error');
    card.setAttribute('role', 'alertdialog');
    card.append(element('div', 'notice-title', title));
    if (text) card.append(element('p', 'notice-text', text));
    if (detail) card.append(element('div', 'notice-detail', detail));

    const buttons = [...actions];
    if (closable) buttons.push({ label: 'Close', primary: true, onClick: hideNotice });
    if (buttons.length > 0) {
        const bar = element('div', 'notice-actions');
        for (const action of buttons) {
            const button = element('button', action.primary ? 'notice-button primary' : 'notice-button', action.label);
            button.type = 'button';
            button.addEventListener('click', action.onClick);
            bar.append(button);
        }
        card.append(bar);
    }
    show(card, { kind: 'error', closable });
}

/**
 * Shows the start card of a run whose first data is not there yet.
 * @param {string} runLabel - Short form of the run's id.
 */
export function showStartNotice(runLabel) {
    const card = element('div', 'notice notice-start');
    card.setAttribute('role', 'status');
    card.append(
        element('div', 'notice-start-logo', 'EVOCHORA'),
        element('div', 'notice-title', 'Your simulation is starting'),
        element('p', 'notice-text', 'The first data appears here as soon as it has been indexed.')
    );

    const progress = element('div', 'notice-progress');
    const fill = element('div', 'notice-progress-fill');
    progress.append(fill);

    const phases = element('div', 'notice-phases');
    const simulating = element('span', 'active', 'Simulating first chunk');
    const indexing = element('span', '', 'Indexing');
    phases.append(simulating, indexing);

    const stats = element('div', 'notice-stats');
    const ticks = element('b', '', '—');
    const speed = element('b', '', '—');
    const ticksBox = element('div');
    ticksBox.append(ticks, 'ticks');
    const speedBox = element('div');
    speedBox.append(speed, 'ticks / s');
    stats.append(ticksBox, speedBox);

    const log = element('div', 'notice-log');
    log.hidden = true;

    card.append(progress, phases, stats, log, element('div', 'notice-run', `Run ${runLabel}`));
    show(card, { kind: 'start', closable: false, parts: { progress, fill, simulating, indexing, ticks, speed, log } });
}

/**
 * Brings the start card up to date; does nothing if it is not shown.
 * @param {{indexing: boolean, fraction: number, ticks: number|null, ticksPerSecond: number|null, failed?: string[]}} progress
 *        fraction is the part of the first chunk simulated so far, between 0 and 1; failed names
 *        the pipeline services that stopped with an error, listed on the card without ending it
 */
export function updateStartNotice(progress) {
    if (current?.kind !== 'start') return;
    const p = current.parts;
    const share = progress.indexing ? SIMULATION_SHARE : SIMULATION_SHARE * Math.min(1, Math.max(0, progress.fraction));
    p.fill.style.width = `${(share * 100).toFixed(1)}%`;
    p.progress.classList.toggle('indexing', progress.indexing);
    p.simulating.classList.toggle('active', !progress.indexing);
    p.indexing.classList.toggle('active', progress.indexing);
    p.ticks.textContent = formatNumber(progress.ticks);
    p.speed.textContent = formatNumber(progress.ticksPerSecond);

    const failed = progress.failed || [];
    p.log.hidden = failed.length === 0;
    p.log.replaceChildren(...failed.map(name =>
        element('div', 'notice-log-line', `${name} stopped with an error. Data for this view may not arrive; see the node log.`)));
}

/** Removes the start card; leaves any other notice alone. */
export function hideStartNotice() {
    if (current?.kind === 'start') hideNotice();
}

/** Removes a closable error card, as when the user moves on; notices that must stay are kept. */
export function dismissClosableNotice() {
    if (current?.closable) hideNotice();
}

/** Removes whatever notice is shown. */
export function hideNotice() {
    if (scrim) {
        scrim.remove();
        scrim = null;
    }
    current = null;
}

// ── Private ──────────────────────────────────────────

function show(card, state) {
    hideNotice();
    scrim = element('div', state.closable ? 'notice-scrim notice-light' : 'notice-scrim');
    scrim.append(card);
    document.body.append(scrim);
    current = state;
}

function element(tag, className = '', text = null) {
    const el = document.createElement(tag);
    if (className) el.className = className;
    if (text !== null) el.textContent = text;
    return el;
}

function formatNumber(value) {
    return value === null || value === undefined ? '—' : Math.round(value).toLocaleString('en-US');
}

document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && current?.closable) hideNotice();
});
