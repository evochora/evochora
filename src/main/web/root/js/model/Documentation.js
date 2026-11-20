/**
 * The documents the start page links, and where each link leads for the build of this node.
 *
 * A release links the documents as its tag holds them, so that a reader sees what belongs to the
 * program in front of them. A build made for no release links the main branch.
 *
 * @module Documentation
 */

const REPOSITORY = 'https://github.com/evochora/evochora';
const PLUGIN_SOURCES = 'src/main/java/org/evochora/';

/** The branch a build made for no release links */
export const DEFAULT_REF = 'main';

/** How the node names a build made for no release: by this word and the git revision */
const REVISION_PREFIX = 'revision ';
const SHORT_REVISION_LENGTH = 7;

const file = path => ref => REPOSITORY + '/blob/' + ref + '/' + path;
const directory = path => ref => REPOSITORY + '/tree/' + ref + '/' + path;
const fixed = url => () => url;
const userGuide = anchor => file('docs/USER_GUIDE.md' + (anchor ? '#' + anchor : ''));

const WORK = [
    { label: 'Run Experiments', emphasis: true,
        text: 'Follow the User Guide through a full experiment, from the first start to sharing its results, and look up every command in the CLI Usage Guide.',
        links: [['User Guide', userGuide()], ['CLI Usage Guide', file('docs/CLI_USAGE.md')]] },
    { label: 'Configure an Experiment',
        text: 'Learn in the User Guide how to set up an experiment, start from the template with the most important settings, and look up every setting in the full reference.',
        links: [['User Guide', userGuide('3-configure-an-experiment')], ['evochora.conf', file('config/evochora.conf')],
            ['reference.conf', file('src/main/resources/reference.conf')]] },
    { label: 'Write Organisms',
        text: 'Start from an ancestor that comes with Evochora, reuse behaviors from the library, and learn the EvoASM language.',
        links: [['User Guide', userGuide('2-choose-or-write-an-ancestor')], ['Ancestors', file('assembly/ancestors/README.md')],
            ['Behavior Library', file('assembly/lib/behavior/README.md')], ['Assembly Specification', file('docs/EVOASM_SPEC.md')],
            ['EvoASM Guidelines', file('docs/EVOASM_GUIDELINES.md')], ['Syntax Highlighting', file('extensions/vscode/README.md')]] },
    { label: 'Analyze the Data',
        text: 'Write analyses of a run\'s data in Python. The User Guide and the notebook show how, and plugins let you add further metrics.',
        links: [['User Guide', userGuide('7-analyze-the-data')], ['Analysis Notebook', file('notebooks/README.md')],
            ['Analytics Plugins', directory(PLUGIN_SOURCES + 'datapipeline/services/analytics/plugins')]] }
];
const MORE = [
    { label: 'Build Plugins', links: [['User Guide', userGuide('10-extend-evochora-with-plugins')], ['Reference Plugins', directory(PLUGIN_SOURCES + 'runtime/worldgen')]] },
    { label: 'API Documentation', links: [['HTTP API Reference', fixed('/api-docs/')]] },
    { label: 'Understand the Project', links: [['Scientific Overview', file('docs/SCIENTIFIC_OVERVIEW.md')],
        ['Published Experiments', file('docs/PUBLISHED_EXPERIMENTS.md')], ['Origin Story', file('docs/ORIGIN_STORY.md')]] },
    { label: 'Work on the Code', links: [['Development Guide', file('docs/dev/DEVELOPMENT.md')], ['Architecture', file('docs/dev/ARCHITECTURE.md')],
        ['Architecture & Design Decisions', file('docs/dev/ARCHITECTURE_DECISIONS.md')]] },
    { label: 'Contribute', links: [['Contributing Guide', file('CONTRIBUTING.md')], ['AI Policy', file('AI_POLICY.md')], ['Code of Conduct', file('CODE_OF_CONDUCT.md')]] },
    { label: 'Get Help', links: [['Discord', fixed('https://discord.gg/t9yEJc4MKX')], ['Discussions', fixed(REPOSITORY + '/discussions')],
        ['Issues', fixed(REPOSITORY + '/issues')]] }
];
const PROJECT = [['MIT License', file('LICENSE')], ['GitHub', fixed(REPOSITORY)]];
const STARTING_RUNS = ['Start and Manage Runs', userGuide('4-start-and-manage-runs')];

/**
 * Describes the build of the node from the version its API specification names.
 *
 * @param {string|null|undefined} version - The release tag, or "revision <hash>" for a build made
 *     for no release
 * @returns {null|{ref: string, release: string}|{ref: string, revision: string, shortRevision: string, title: string}}
 *     null where the node names no version. A release is linked by its tag; any other build is
 *     linked by the main branch and shown by its revision.
 */
export function describeBuild(version) {
    if (!version) return null;
    if (!version.startsWith(REVISION_PREFIX)) return { ref: version, release: version };
    const revision = version.slice(REVISION_PREFIX.length);
    return { ref: DEFAULT_REF, revision, shortRevision: revision.slice(0, SHORT_REVISION_LENGTH), title: version };
}

/**
 * @param {[string, function(string): string]} link - A label and where it leads for a ref
 * @param {string} ref
 * @returns {{label: string, url: string, external: boolean}} external tells whether the link
 *     leaves the node
 */
function resolve([label, target], ref) {
    const url = target(ref);
    return { label, url, external: !url.startsWith('/') };
}

/**
 * The documents of the start page with their links resolved for a ref.
 *
 * @param {string} ref - The tag of a release, or the main branch
 * @returns {{work: Array<{label: string, text: string, emphasis: boolean, links: Array}>,
 *     more: Array<{label: string, links: Array}>, project: Array, startingRuns: object}}
 *     work are the topics with a text, more the groups in the foot, project the links to the
 *     project itself, and startingRuns the chapter a node without runs points to
 */
export function documentationFor(ref) {
    const links = list => list.map(link => resolve(link, ref));
    return {
        work: WORK.map(topic => ({ label: topic.label, text: topic.text, emphasis: !!topic.emphasis, links: links(topic.links) })),
        more: MORE.map(topic => ({ label: topic.label, links: links(topic.links) })),
        project: links(PROJECT),
        startingRuns: resolve(STARTING_RUNS, ref)
    };
}
