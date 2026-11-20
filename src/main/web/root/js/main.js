/**
 * The start page of a node: its state, its runs, and the documents that belong to its build.
 *
 * This module asks the node, lets the model decide what there is to say, and hands that to the
 * views. It reads the state of the node every second and the list of runs less often; what never
 * changes about a run is read once.
 */

import { AppSwitcher } from '/shared/app-switcher/AppSwitcher.js';
import { readApiSpecification, readMetadata, readOrganisms, readPipelineStatus, readRuns, readTickRange, requestSimulation } from './api/NodeApi.js';
import { DEFAULT_REF, describeBuild, documentationFor } from './model/Documentation.js';
import { NODE_LABEL, activeRunIdOf, describeRuns, engineOf, factsOf, hasTicks, nodeStateOf, orderRunIds, paceText, recordedOf } from './model/Runs.js';
import { drawDocumentation } from './ui/DocumentationView.js';
import { runTableIn } from './ui/RunTable.js';

const REFRESH_INTERVAL_MS = 1000;
const RUN_LIST_INTERVAL_MS = 15000;
/** How long a request to pause or continue may await its effect before its button is offered again */
const TOGGLE_TIMEOUT_MS = 8000;

const pageParams = new URLSearchParams(window.location.search);
/** What the links to the tools pass on from the address of this page */
const carried = { tick: pageParams.get('tick'), organism: pageParams.get('organism') };

try {
    const apps = await (await fetch('/shared/app-switcher/apps.json')).json();
    new AppSwitcher({
        element: document.getElementById('app-switcher-container'),
        apps,
        getState: () => ({ ...carried, runId: pageParams.get('runId') })
    });
} catch (e) {
    console.error('Failed to initialize AppSwitcher:', e);
}

// ── Documentation ──
const documentationPlaces = { work: document.getElementById('work'), more: document.getElementById('more') };
let documentation = documentationFor(DEFAULT_REF);
drawDocumentation(documentationPlaces, documentation, null);
readApiSpecification().then(specification => {
    const build = describeBuild(specification && specification.info ? specification.info.version : null);
    if (build) documentation = documentationFor(build.ref);
    drawDocumentation(documentationPlaces, documentation, build);
});

// ── Runs and the state of the node ──
const nodeElement = document.getElementById('node');
const paceElement = document.getElementById('pace');
const facts = {};        // runId -> what never changes: name, environment, sampling
const recorded = {};     // runId -> what was last read: minTick, maxTick, alive, born
let runIds = [];
let runListReadAt = 0;
let pendingToggle = null;   // { leaving: the engine state the click wants to leave, since }

// The node accepts every pause and resume request, so the outcome is read from its state afterwards
const showRuns = runTableIn(document.getElementById('runs'), action => {
    pendingToggle = { leaving: action === 'pause' ? 'RUNNING' : 'PAUSED', since: Date.now() };
    requestSimulation(action);
});

function showNode(state, pace) {
    nodeElement.firstElementChild.hidden = false;
    nodeElement.className = 'node ' + state;
    nodeElement.children[1].textContent = NODE_LABEL[state];
    paceElement.textContent = pace;
}

async function readFacts(runId) {
    if (facts[runId]) return;
    const metadata = await readMetadata(runId);
    if (metadata) facts[runId] = factsOf(metadata);
}

async function readRecorded(runId) {
    const ticks = await readTickRange(runId);
    if (!hasTicks(ticks)) return;
    const known = recorded[runId];
    if (known && known.maxTick === ticks.maxTick) return;
    recorded[runId] = recordedOf(ticks, await readOrganisms(runId, ticks.maxTick));
}

async function refresh() {
    const status = await readPipelineStatus();
    if (!status) {
        showNode('unreachable', '');
        showRuns({ kind: 'unreachable' });
        return;
    }
    const engine = engineOf(status);
    const activeRunId = activeRunIdOf(status);

    if (Date.now() - runListReadAt > RUN_LIST_INTERVAL_MS || (activeRunId && !runIds.includes(activeRunId))) {
        const listed = await readRuns();
        if (listed) {
            runIds = orderRunIds(listed, activeRunId);
            runListReadAt = Date.now();
        }
    }
    // A run that is not simulated records nothing more: what it has recorded is read once
    await Promise.all(runIds.map(runId => Promise.all([readFacts(runId), runId === activeRunId || !recorded[runId] ? readRecorded(runId) : null])));

    const state = nodeStateOf(status, !!recorded[activeRunId]);
    if (pendingToggle && (engine.state !== pendingToggle.leaving || Date.now() - pendingToggle.since > TOGGLE_TIMEOUT_MS)) pendingToggle = null;
    showNode(state, paceText(state, engine));

    if (!runIds.length) {
        showRuns({ kind: 'empty', guide: documentation.startingRuns });
        return;
    }
    showRuns({ kind: 'table', ...describeRuns({ runIds, facts, recorded, activeRunId, state, togglePending: !!pendingToggle, carried }) });
}

let refreshing = false;
async function tick() {
    if (refreshing) return;
    refreshing = true;
    try { await refresh(); } finally { refreshing = false; document.getElementById('logo').classList.remove('loading'); }
}
tick();
setInterval(tick, REFRESH_INTERVAL_MS);
