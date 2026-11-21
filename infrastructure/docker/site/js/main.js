/**
 * The homepage of evochora.org: the runs of its Live Demo, the download of the latest release and
 * the published experiments, and the jump bar that marks the section in view.
 *
 * This module asks the node, GitHub and Zenodo, lets the model decide what there is to show, and
 * hands that to the views. Each part of the page is drawn as soon as its own answers are in.
 *
 * The homepage is served beside the node, not by it, and has to work while no node answers. It
 * therefore binds no module of the node into its own: the app switcher, which the node serves, is
 * loaded on its own, and without it the page lacks the switcher and nothing else.
 */

import { readLatestRelease } from './api/GitHubApi.js';
import { readMetadata, readOrganisms, readRuns, readTickRange } from './api/NodeApi.js';
import { readCommunityRecords, readVersionRecords } from './api/ZenodoApi.js';
import { describeEntry, hasTicks, orderRunIds } from './model/Demo.js';
import { ZENODO_CONCEPT_RECORD, conceptDoi, describeDownload, systemOf, versionDoi } from './model/Download.js';
import { describeExperiments } from './model/Experiments.js';
import { sectionInView } from './model/JumpBar.js';
import { drawDemo } from './ui/DemoView.js';
import { drawConceptDoi, drawDownload, drawVersionDoi } from './ui/DownloadView.js';
import { drawExperiments } from './ui/ExperimentsView.js';

const byId = id => document.getElementById(id);

// ── App switcher ──
const pageParams = new URLSearchParams(window.location.search);
Promise.all([
    import('/shared/app-switcher/AppSwitcher.js'),
    fetch('/shared/app-switcher/apps.json').then(response => response.json())
]).then(([{ AppSwitcher }, apps]) => {
    new AppSwitcher({
        element: byId('app-switcher-container'),
        apps,
        getState: () => ({ tick: pageParams.get('tick'), organism: pageParams.get('organism'), runId: pageParams.get('runId') })
    });
}).catch(e => console.error('Failed to initialize AppSwitcher:', e));

// ── Live Demo ──
async function readEntry(runId) {
    const [metadata, tickRange] = await Promise.all([readMetadata(runId), readTickRange(runId)]);
    const organisms = hasTicks(tickRange) ? await readOrganisms(runId, tickRange.maxTick) : null;
    return describeEntry(runId, metadata, tickRange, organisms);
}

readRuns().then(async listed => {
    drawDemo(byId('demo-runs'), listed && listed.length ? await Promise.all(orderRunIds(listed).map(readEntry)) : null);
});

// ── Published experiments ──
readCommunityRecords().then(answer => drawExperiments(byId('exp-list'), describeExperiments(answer)));

// ── Download, and the DOIs to cite Evochora by ──
const citation = conceptDoi(ZENODO_CONCEPT_RECORD);
if (citation) drawConceptDoi(byId('cite-doi'), citation);

readLatestRelease().then(release => {
    const platform = (navigator.userAgentData && navigator.userAgentData.platform) || navigator.platform || '';
    const download = describeDownload(release, systemOf(platform, navigator.userAgent));
    if (!download) return;
    drawDownload({ button: byId('dl-button'), version: byId('dl-version'), separator: byId('dl-version-sep'), others: byId('dl-other') }, download);
    if (citation) {
        readVersionRecords(ZENODO_CONCEPT_RECORD).then(answer => {
            const doi = versionDoi(answer, release.tag_name);
            if (doi) drawVersionDoi(byId('dl-doi'), doi);
        });
    }
});

// ── Jump bar ──
const jumps = [...document.querySelectorAll('#site-nav a')];
const sections = jumps.map(a => byId(a.getAttribute('href').slice(1)));

function markSectionInView() {
    const tops = sections.map(section => section ? section.getBoundingClientRect().top : null);
    const current = sectionInView(tops, { viewHeight: window.innerHeight, scrollY: window.scrollY, pageHeight: document.documentElement.scrollHeight });
    jumps.forEach((a, i) => a.classList.toggle('here', i === current));
}
window.addEventListener('scroll', markSectionInView, { passive: true });
window.addEventListener('resize', markSectionInView);
markSectionInView();
