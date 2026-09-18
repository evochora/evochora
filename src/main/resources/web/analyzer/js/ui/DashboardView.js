import * as MetricCardView from './MetricCardView.js';

/**
 * Dashboard View
 * 
 * Manages the main dashboard grid, creating and holding metric cards.
 * 
 * @module DashboardView
 */

let container = null;
let nav = null;

/** Name of the group that holds the metrics whose plugin names none. */
const UNGROUPED = 'Other';

/** URL parameter that carries the group shown. */
const GROUP_PARAM = 'group';

/** Groups of the dashboard shown, in the order the manifest first names them. */
let groups = [];
let activeGroup = null;
let onGroupChange = () => {};

/**
 * Initializes the dashboard view.
 */
export function init() {
    container = document.getElementById('dashboard-container');
    nav = document.getElementById('group-nav');
    if (!container) {
        console.error('[DashboardView] Dashboard container not found');
    }
    if (nav) {
        new ResizeObserver(fitNavigation).observe(nav);
        document.addEventListener('click', event => {
            if (!event.target.closest('.group-nav-more')) closeMenu();
        });
    }
}

/**
 * Sets the handler called after another group was brought into view.
 *
 * @param {function(string)} handler - Receives the name of the group now shown
 */
export function setOnGroupChange(handler) {
    onGroupChange = handler || (() => {});
}

/**
 * Clears the dashboard and creates metric cards from a manifest.
 *
 * The metrics are laid out by the group their manifest entry names. Every group is a page of its
 * own, reached through the header, which lists the groups in the order the manifest first names
 * them; a dashboard with a single group has no navigation. Metrics without a group share one
 * that comes last.
 *
 * @param {Array<Object>} metrics - Metric manifest entries
 */
export function createCards(metrics) {
    if (!container) return;

    MetricCardView.reset();
    container.innerHTML = '';

    const byGroup = new Map();
    metrics.forEach(metric => {
        const name = metric.group || UNGROUPED;
        if (!byGroup.has(name)) byGroup.set(name, []);
        byGroup.get(name).push(metric);
    });
    if (byGroup.has(UNGROUPED)) {
        const ungrouped = byGroup.get(UNGROUPED);
        byGroup.delete(UNGROUPED);
        byGroup.set(UNGROUPED, ungrouped);
    }
    groups = [...byGroup.keys()];

    byGroup.forEach((groupMetrics, name) => {
        const page = document.createElement('section');
        page.className = 'dashboard';
        page.dataset.group = name;
        groupMetrics.forEach(metric => {
            page.appendChild(MetricCardView.create(metric));
            MetricCardView.getAllCards()[metric.id].group = name;
        });
        container.appendChild(page);
    });

    createNavigation(byGroup);

    const requested = new URLSearchParams(window.location.search).get(GROUP_PARAM);
    activeGroup = null;
    showGroup(groups.find(name => slug(name) === requested) || groups[0], false);
}

/**
 * Fills the header navigation with one item per group, followed by the menu that takes the items
 * the header has no room for.
 *
 * @param {Map<string, Array<Object>>} byGroup - Metrics by group name
 */
function createNavigation(byGroup) {
    if (!nav) return;
    nav.innerHTML = '';
    nav.hidden = groups.length < 2;
    if (nav.hidden) return;

    const menu = document.createElement('div');
    menu.className = 'group-nav-menu';
    menu.hidden = true;

    groups.forEach(name => {
        [nav, menu].forEach(parent => {
            const item = document.createElement('button');
            item.className = 'group-nav-item';
            item.dataset.group = name;
            item.innerHTML = '<span class="group-nav-name"></span><span class="group-nav-count"></span>';
            item.querySelector('.group-nav-name').textContent = name;
            item.querySelector('.group-nav-count').textContent = byGroup.get(name).length;
            item.addEventListener('click', () => {
                closeMenu();
                showGroup(name);
            });
            parent.appendChild(item);
        });
    });

    const more = document.createElement('button');
    more.className = 'group-nav-item group-nav-more';
    more.setAttribute('aria-haspopup', 'true');
    more.addEventListener('click', () => {
        menu.hidden = !menu.hidden;
    });
    nav.appendChild(more);
    nav.appendChild(menu);
}

/**
 * Shows as many groups in the header as fit and leaves the others to the menu. The menu button
 * carries the name of the group in view when that group is one of those left to the menu.
 */
function fitNavigation() {
    if (!nav || nav.hidden) return;
    const more = nav.querySelector('.group-nav-more');
    const menu = nav.querySelector('.group-nav-menu');
    const items = [...nav.querySelectorAll(':scope > .group-nav-item:not(.group-nav-more)')];
    const label = hiddenNames => {
        const current = hiddenNames.includes(activeGroup);
        more.textContent = `${current ? activeGroup : 'More'} \u25BE`;
        more.classList.toggle('active', current);
    };

    items.forEach(item => { item.hidden = false; });
    more.hidden = true;
    let shown = items.length;
    while (shown > 0 && nav.scrollWidth > nav.clientWidth) {
        shown--;
        items[shown].hidden = true;
        more.hidden = false;
        label(groups.slice(shown));
    }
    if (shown === items.length) closeMenu();
    menu.querySelectorAll('.group-nav-item').forEach((item, index) => {
        item.hidden = index < shown;
    });
}

/** Empties the header navigation, as when a message takes the place of the dashboard. */
function clearNavigation() {
    groups = [];
    activeGroup = null;
    if (nav) {
        nav.innerHTML = '';
        nav.hidden = true;
    }
}

/** Closes the menu of the groups the header has no room for. */
function closeMenu() {
    const menu = nav && nav.querySelector('.group-nav-menu');
    if (menu) menu.hidden = true;
}

/**
 * Brings a group into view and hides the others. The cards of a hidden group stay as they are, so
 * that coming back finds their level of detail and scroll position unchanged.
 *
 * @param {string} name - Group name
 * @param {boolean} [notify=true] - Whether the group change handler is called
 */
export function showGroup(name, notify = true) {
    if (!container || !groups.includes(name) || name === activeGroup) return;
    activeGroup = name;
    container.querySelectorAll('.dashboard').forEach(page => {
        page.hidden = page.dataset.group !== name;
    });
    if (nav) {
        nav.querySelectorAll('.group-nav-item:not(.group-nav-more)').forEach(item => {
            const current = item.dataset.group === name;
            item.classList.toggle('active', current);
            item.setAttribute('aria-current', current ? 'page' : 'false');
        });
        fitNavigation();
    }

    const url = new URL(window.location.href);
    const runId = url.searchParams.get('runId');
    url.searchParams.delete('runId');
    if (groups.length > 1) url.searchParams.set(GROUP_PARAM, slug(name));
    else url.searchParams.delete(GROUP_PARAM);
    if (runId) url.searchParams.set('runId', runId);
    window.history.replaceState({}, '', url.toString());

    if (notify) onGroupChange(name);
}

/**
 * Gets the card instances of the group in view.
 *
 * @returns {Array<Object>} Card instances
 */
export function getActiveCards() {
    return Object.values(MetricCardView.getAllCards()).filter(card => card.group === activeGroup);
}

/**
 * Turns a group name into the form it has in the URL.
 *
 * @param {string} name - Group name
 * @returns {string}
 */
function slug(name) {
    return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
}

/**
 * Gets all metric card instances from the view.
 * 
 * @returns {Object<string, Object>} Map of metric ID to card instance
 */
export function getAllCards() {
    return MetricCardView.getAllCards();
}

/**
 * Displays a message in the dashboard area (e.g., loading, error).
 * 
 * @param {string} message
 * @param {boolean} [isError=false]
 */
export function showMessage(message, isError = false) {
    if (!container) return;
    clearNavigation();
    
    container.innerHTML = `
        <div class="message ${isError ? 'error' : ''}">${message}</div>
    `;
}

/**
 * Displays an empty state message (no data for run).
 */
export function showEmptyState(message) {
    if (!container) return;
    clearNavigation();
    
    container.innerHTML = `
        <div class="empty-state">
            <h2>No Data</h2>
            <p>${message}</p>
        </div>
    `;
}

