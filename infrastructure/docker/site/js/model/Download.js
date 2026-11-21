/**
 * What the download offers, worked out from the latest published release on GitHub and the system
 * of the visitor, and the DOIs under which Evochora is archived on Zenodo.
 *
 * @module Download
 */

/**
 * The number of Evochora's concept record on Zenodo. It exists from the first archived release on
 * and never changes; while it is empty, no DOI is shown.
 */
export const ZENODO_CONCEPT_RECORD = '';

const DOI_PREFIX = '10.5281/zenodo.';

/** The systems a release can have an archive for, in the order the download names them */
export const SYSTEMS = { 'linux-x64': 'Linux (x64)', 'linux-aarch64': 'Linux (ARM64)', 'macos-aarch64': 'macOS (Apple silicon)', 'windows-x64': 'Windows (x64)' };

/**
 * The systems Evochora is known not to support, each as the sentence about it ends. No archive
 * runs there as it is shipped, the one without a Java runtime included: a library the analysis
 * needs brings its native part for the systems of SYSTEMS alone.
 */
const UNSUPPORTED = { 'macos-x64': 'a Mac with an Intel processor' };

/** How the name of an archive for one system ends: the system, then the kind of archive */
const SYSTEM_ARCHIVE = /-((?:linux|macos|windows)-[a-z0-9]+)\.(?:tar\.gz|zip)$/;

const BYTES_PER_MEGABYTE = 1e6;

/**
 * The system of the visitor, as the archives of a release name it.
 *
 * Where the browser names the processor when asked, that answer decides; only some browsers do.
 * Without it, a Mac is taken for one with Apple silicon, since platform and user agent read the
 * same on both kinds of Mac, and Linux for x64 unless platform or user agent name ARM: a browser
 * that answers the question carries a user agent that says x86_64 on every processor. Windows has
 * one archive, which Windows runs on ARM as well.
 *
 * @param {string} platform - What the browser names as its platform
 * @param {string} userAgent
 * @param {string|null} [architecture] - The processor family as the browser names it when asked,
 *     "arm" or "x86", or null where it does not tell
 * @returns {string|null} A key of SYSTEMS, a key of UNSUPPORTED for a system Evochora is known
 *     not to support, or null for a system that is not recognised
 */
export function systemOf(platform, userAgent, architecture = null) {
    const p = platform.toLowerCase(), ua = userAgent.toLowerCase();
    if (p.includes('win')) return 'windows-x64';
    if (p.includes('mac')) return architecture === 'x86' ? 'macos-x64' : 'macos-aarch64';
    if (p.includes('linux') || ua.includes('linux')) {
        if (architecture === 'arm') return 'linux-aarch64';
        if (architecture === 'x86') return 'linux-x64';
        return /aarch64|arm64/.test(ua + ' ' + p) ? 'linux-aarch64' : 'linux-x64';
    }
    return null;
}

/**
 * @param {Array<{name: string}>} assets
 * @returns {Object<string, object>} The archive of each system the release has one for
 */
function archivesBySystem(assets) {
    const found = {};
    for (const asset of assets) {
        const m = asset.name.match(SYSTEM_ARCHIVE);
        if (m && SYSTEMS[m[1]]) found[m[1]] = asset;
    }
    return found;
}

/**
 * Describes the download.
 *
 * @param {{tag_name: string, html_url: string, assets: Array<{name: string, size: number,
 *     browser_download_url: string}>}|null} release - The latest release, or null where GitHub did
 *     not answer
 * @param {string|null} system - The system of the visitor, as systemOf names it
 * @returns {null|{button: {url: string, label: string, newTab: boolean}, version: string,
 *     others: null|{label: string, links: Array<{label: string, url: string}>}, notice?: string}}
 *     null where there is no release: the button to the latest release stays as the page has it.
 *     The button leads to an archive, or opens the page of the release in a new tab; others are the
 *     archives named beside the version: those for further systems, or all of them where the
 *     button leads to the release. The notice is there for a visitor on a system Evochora is known
 *     not to support, and says so.
 */
export function describeDownload(release, system) {
    if (!release) return null;
    const assets = release.assets || [];
    const version = 'Version ' + release.tag_name;
    const offer = (asset, label) => ({
        button: { url: asset.browser_download_url, label, newTab: false },
        version: version + ', ' + Math.round(asset.size / BYTES_PER_MEGABYTE) + ' MB',
        others: null
    });

    // An archive for each system: the visitor's one is offered, the others are named beside it. A
    // visitor for whom the release has none, because the system is not recognised or its archive is
    // missing, is led to the release, beside every archive it has, and not offered the archive of
    // another system.
    const found = archivesBySystem(assets);
    const systems = Object.keys(SYSTEMS).filter(key => found[key]);
    const archiveLink = key => ({ label: SYSTEMS[key], url: found[key].browser_download_url });
    if (systems.length && !found[system]) {
        const notice = UNSUPPORTED[system] ? { notice: 'Evochora is not supported on ' + UNSUPPORTED[system] + '.' } : {};
        return { button: { url: release.html_url, label: 'Download', newTab: true }, version, others: { label: 'Available for', links: systems.map(archiveLink) }, ...notice };
    }
    if (systems.length) {
        return {
            ...offer(found[system], 'Download for ' + SYSTEMS[system]),
            others: { label: 'Also for', links: systems.filter(key => key !== system).map(archiveLink) }
        };
    }

    // Only archives for every system alike: the zip on Windows, the tar.gz elsewhere
    const general = assets.find(asset => system === 'windows-x64' ? /\.zip$/.test(asset.name) : /\.tar\.gz$/.test(asset.name));
    if (general) return offer(general, 'Download');

    // No archive at all: the button leads to the release, its version is named
    return { button: { url: release.html_url, label: 'Download', newTab: true }, version, others: null };
}

/**
 * The DOI of Evochora as a whole, across all its versions.
 *
 * @param {string} conceptRecord - The number of the concept record, or empty while there is none
 * @returns {{label: string, url: string}|null}
 */
export function conceptDoi(conceptRecord) {
    if (!conceptRecord) return null;
    return { label: 'DOI ' + DOI_PREFIX + conceptRecord, url: 'https://doi.org/' + DOI_PREFIX + conceptRecord };
}

/**
 * The DOI of one version: the record on Zenodo whose version equals the tag of the release.
 *
 * @param {{hits?: {hits?: Array<{doi: string, doi_url: string, metadata: {version: string}}>}}|null} answer -
 *     What Zenodo answers for the versions of the concept record
 * @param {string} tag - The tag of the release
 * @returns {{doi: string, url: string}|null} null where no version carries the tag
 */
export function versionDoi(answer, tag) {
    const records = answer && answer.hits && Array.isArray(answer.hits.hits) ? answer.hits.hits : [];
    const record = records.find(r => r.metadata.version === tag);
    return record ? { doi: record.doi, url: record.doi_url } : null;
}
