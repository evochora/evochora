import { describe, expect, test } from 'vitest';
import { DEFAULT_REF, describeBuild, documentationFor } from '../../../../../main/web/root/js/model/Documentation.js';

const REPOSITORY = 'https://github.com/evochora/evochora';

describe('the build of a node', () => {
    test('is unknown where the node names no version', () => {
        expect(describeBuild(null)).toBeNull();
        expect(describeBuild('')).toBeNull();
    });

    test('is a release when the node names a tag, and links the documents of that tag', () => {
        expect(describeBuild('v1.0.0')).toEqual({ ref: 'v1.0.0', release: 'v1.0.0' });
    });

    test('is shown by its revision when it was made for no release, and links the main branch', () => {
        const revision = 'a508fd162783b52d606652558b7f6c7fda3f881e';
        expect(describeBuild('revision ' + revision)).toEqual({ ref: DEFAULT_REF, revision, shortRevision: 'a508fd1', title: 'revision ' + revision });
    });

    test('keeps the mark of a build with uncommitted changes in the revision it copies', () => {
        expect(describeBuild('revision a508fd162783b52d-dirty').revision).toBe('a508fd162783b52d-dirty');
    });
});

describe('the documents of the start page', () => {
    const all = documentation => [...documentation.work, ...documentation.more].flatMap(topic => topic.links)
        .concat(documentation.project, [documentation.startingRuns]);

    test('lead to the tag of a release', () => {
        const guide = documentationFor('v1.0.0').work[0].links[0];
        expect(guide).toEqual({ label: 'User Guide', url: REPOSITORY + '/blob/v1.0.0/docs/USER_GUIDE.md', external: true });
    });

    test('lead to the main branch for a build made for no release', () => {
        expect(documentationFor(DEFAULT_REF).work[0].links[0].url).toBe(REPOSITORY + '/blob/main/docs/USER_GUIDE.md');
    });

    test('link a directory as a tree and a document as a file', () => {
        const analyze = documentationFor('v1.0.0').work.find(topic => topic.label === 'Analyze the Data');
        expect(analyze.links.find(link => link.label === 'Analytics Plugins').url).toMatch(/\/tree\/v1\.0\.0\/src\/main\/java\/org\/evochora\//);
    });

    test('keep the links that belong to no version the same for every ref', () => {
        const fixed = documentation => all(documentation).filter(link => !link.url.includes('/blob/') && !link.url.includes('/tree/')).map(link => link.url);
        expect(fixed(documentationFor('v1.0.0'))).toEqual(fixed(documentationFor(DEFAULT_REF)));
        expect(fixed(documentationFor(DEFAULT_REF))).toContain('/api-docs/');
    });

    test('tell the links that stay on the node from those that leave it', () => {
        for (const link of all(documentationFor(DEFAULT_REF))) expect(link.external).toBe(!link.url.startsWith('/'));
        const api = documentationFor(DEFAULT_REF).more.find(topic => topic.label === 'API Documentation').links[0];
        expect(api).toEqual({ label: 'HTTP API Reference', url: '/api-docs/', external: false });
    });

    test('mark the one topic a newcomer starts with', () => {
        expect(documentationFor(DEFAULT_REF).work.filter(topic => topic.emphasis).map(topic => topic.label)).toEqual(['Run Experiments']);
    });

    test('point a node without runs to the chapter on starting one', () => {
        expect(documentationFor('v1.0.0').startingRuns.url).toBe(REPOSITORY + '/blob/v1.0.0/docs/USER_GUIDE.md#4-start-and-manage-runs');
    });

    test('name every link and give it a place to lead to', () => {
        for (const link of all(documentationFor(DEFAULT_REF))) {
            expect(link.label).not.toBe('');
            expect(link.url).toMatch(/^(\/|https:\/\/)/);
        }
    });
});
