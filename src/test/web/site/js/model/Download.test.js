import { describe, expect, test } from 'vitest';
import { conceptDoi, describeDownload, systemOf, versionDoi } from '../../../../../../infrastructure/docker/site/js/model/Download.js';

const PAGE = 'https://github.com/evochora/evochora/releases/tag/v1.0.0';
const archive = name => ({ name, size: 162762011, browser_download_url: 'https://github.com/evochora/evochora/releases/download/v1.0.0/' + name });
const release = names => ({ tag_name: 'v1.0.0', html_url: PAGE, assets: names.map(archive) });

const SYSTEM_ARCHIVES = ['evochora-v1.0.0-linux-aarch64.tar.gz', 'evochora-v1.0.0-linux-x64.tar.gz', 'evochora-v1.0.0-macos-aarch64.tar.gz', 'evochora-v1.0.0-windows-x64.zip'];
const url = name => archive(name).browser_download_url;

describe('the system of a visitor', () => {
    test('is read from the platform', () => {
        expect(systemOf('Win32', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)')).toBe('windows-x64');
        expect(systemOf('Windows', '')).toBe('windows-x64');
        expect(systemOf('MacIntel', 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)')).toBe('macos-aarch64');
        expect(systemOf('Linux x86_64', 'Mozilla/5.0 (X11; Linux x86_64)')).toBe('linux-x64');
    });

    test('is Linux on ARM where platform or user agent name the architecture', () => {
        expect(systemOf('Linux aarch64', 'Mozilla/5.0 (X11; Linux aarch64)')).toBe('linux-aarch64');
        expect(systemOf('', 'Mozilla/5.0 (X11; Linux arm64)')).toBe('linux-aarch64');
    });

    test('is Linux where only the user agent names it', () => {
        expect(systemOf('', 'Mozilla/5.0 (X11; Linux x86_64)')).toBe('linux-x64');
    });

    test('is a Mac with Apple silicon unless the browser names an Intel processor, which Evochora does not support', () => {
        const intelLooking = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)';
        expect(systemOf('macOS', intelLooking, 'arm')).toBe('macos-aarch64');
        expect(systemOf('macOS', intelLooking, 'x86')).toBe('macos-x64');
        expect(systemOf('MacIntel', intelLooking, null)).toBe('macos-aarch64');
    });

    test('is the Linux of the processor the browser names, whatever its user agent says', () => {
        const frozen = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
        expect(systemOf('Linux', frozen, 'arm')).toBe('linux-aarch64');
        expect(systemOf('Linux', frozen, 'x86')).toBe('linux-x64');
        expect(systemOf('Linux', frozen, null)).toBe('linux-x64');
        expect(systemOf('Windows', '', 'arm')).toBe('windows-x64');
    });

    test('is not recognised on a system without an archive', () => {
        expect(systemOf('iPhone', 'Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X)')).toBeNull();
        expect(systemOf('', '')).toBeNull();
    });
});

describe('the download', () => {
    test('is left as the page has it where GitHub does not answer or refuses', () => {
        expect(describeDownload(null, 'linux-x64')).toBeNull();
    });

    test('offers the archive of the visitor\'s system and names the others beside it', () => {
        expect(describeDownload(release(SYSTEM_ARCHIVES), 'macos-aarch64')).toEqual({
            button: { url: url('evochora-v1.0.0-macos-aarch64.tar.gz'), label: 'Download for macOS (Apple silicon)', newTab: false },
            version: 'Version v1.0.0, 163 MB',
            others: { label: 'Also for', links: [
                { label: 'Linux (x64)', url: url('evochora-v1.0.0-linux-x64.tar.gz') },
                { label: 'Linux (ARM64)', url: url('evochora-v1.0.0-linux-aarch64.tar.gz') },
                { label: 'Windows (x64)', url: url('evochora-v1.0.0-windows-x64.zip') }
            ] }
        });
    });

    test('offers the zip on Windows', () => {
        expect(describeDownload(release(SYSTEM_ARCHIVES), 'windows-x64').button.url).toBe(url('evochora-v1.0.0-windows-x64.zip'));
    });

    test('names the other systems where the release has an archive for the visitor\'s system alone', () => {
        expect(describeDownload(release(['evochora-v1.0.0-linux-x64.tar.gz']), 'linux-x64').others).toEqual({ label: 'Also for', links: [] });
    });

    test('ignores an archive for a system it does not know', () => {
        const download = describeDownload(release(['evochora-v1.0.0-freebsd-x64.tar.gz', 'evochora-v1.0.0-linux-x64.tar.gz']), 'linux-x64');
        expect(download.others.links).toEqual([]);
    });

    test('leads a visitor whose system is not recognised to the release, and names every archive', () => {
        expect(describeDownload(release(SYSTEM_ARCHIVES), null)).toEqual({
            button: { url: PAGE, label: 'Download', newTab: true },
            version: 'Version v1.0.0',
            others: { label: 'Available for', links: [
                { label: 'Linux (x64)', url: url('evochora-v1.0.0-linux-x64.tar.gz') },
                { label: 'Linux (ARM64)', url: url('evochora-v1.0.0-linux-aarch64.tar.gz') },
                { label: 'macOS (Apple silicon)', url: url('evochora-v1.0.0-macos-aarch64.tar.gz') },
                { label: 'Windows (x64)', url: url('evochora-v1.0.0-windows-x64.zip') }
            ] }
        });
    });

    test('tells a visitor on a system Evochora does not support, and leads to the release beside every archive', () => {
        const download = describeDownload(release(SYSTEM_ARCHIVES), 'macos-x64');
        expect(download.notice).toBe('Evochora is not supported on a Mac with an Intel processor.');
        expect(download.button).toEqual({ url: PAGE, label: 'Download', newTab: true });
        expect(download.others.label).toBe('Available for');
        expect(download.others.links.map(link => link.label)).toEqual(['Linux (x64)', 'Linux (ARM64)', 'macOS (Apple silicon)', 'Windows (x64)']);
    });

    test('leads a visitor whose system has no archive in the release to the release, and names the archives it has', () => {
        expect(describeDownload(release(['evochora-v1.0.0-linux-x64.tar.gz', 'evochora-v1.0.0-windows-x64.zip']), 'macos-aarch64')).toEqual({
            button: { url: PAGE, label: 'Download', newTab: true },
            version: 'Version v1.0.0',
            others: { label: 'Available for', links: [
                { label: 'Linux (x64)', url: url('evochora-v1.0.0-linux-x64.tar.gz') },
                { label: 'Windows (x64)', url: url('evochora-v1.0.0-windows-x64.zip') }
            ] }
        });
    });

    test('offers the tar.gz of a release with only general archives', () => {
        expect(describeDownload(release(['evochora-v0.4.0.tar.gz', 'evochora-v0.4.0.zip']), 'linux-x64')).toEqual({
            button: { url: url('evochora-v0.4.0.tar.gz'), label: 'Download', newTab: false }, version: 'Version v1.0.0, 163 MB', others: null
        });
        expect(describeDownload(release(['evochora-v0.4.0.tar.gz', 'evochora-v0.4.0.zip']), null).button.url).toBe(url('evochora-v0.4.0.tar.gz'));
    });

    test('offers the zip of a release with only general archives on Windows', () => {
        expect(describeDownload(release(['evochora-v0.4.0.tar.gz', 'evochora-v0.4.0.zip']), 'windows-x64').button.url).toBe(url('evochora-v0.4.0.zip'));
    });

    test('leads to the page of a release without an archive, and names its version', () => {
        const expected = { button: { url: PAGE, label: 'Download', newTab: true }, version: 'Version v1.0.0', others: null };
        expect(describeDownload(release([]), 'linux-x64')).toEqual(expected);
        expect(describeDownload(release(['evochora-v1.0.0.zip']), 'linux-x64')).toEqual(expected);
        expect(describeDownload({ tag_name: 'v1.0.0', html_url: PAGE }, 'linux-x64')).toEqual(expected);
    });
});

describe('the DOIs of Evochora', () => {
    const versions = { hits: { hits: [
        { doi: '10.5281/zenodo.11', doi_url: 'https://doi.org/10.5281/zenodo.11', metadata: { version: 'v0.9.0' } },
        { doi: '10.5281/zenodo.12', doi_url: 'https://doi.org/10.5281/zenodo.12', metadata: { version: 'v1.0.0' } }
    ] } };

    test('are not shown while there is no concept record', () => {
        expect(conceptDoi('')).toBeNull();
    });

    test('name Evochora as a whole by its concept record', () => {
        expect(conceptDoi('10')).toEqual({ label: 'DOI 10.5281/zenodo.10', url: 'https://doi.org/10.5281/zenodo.10' });
    });

    test('name the version offered by the record that carries its tag', () => {
        expect(versionDoi(versions, 'v1.0.0')).toEqual({ doi: '10.5281/zenodo.12', url: 'https://doi.org/10.5281/zenodo.12' });
    });

    test('name no version that Zenodo has not archived, or where Zenodo did not answer', () => {
        expect(versionDoi(versions, 'v1.1.0')).toBeNull();
        expect(versionDoi(null, 'v1.0.0')).toBeNull();
        expect(versionDoi({ status: 500 }, 'v1.0.0')).toBeNull();
    });
});
