# Release Process

**Status: ACCOMPLISHED — implemented on branch `release-prep` (2026-11-21); see *Outcome* at the
end for what the first runs on `main` still have to show.**

## Purpose

Evochora publishes two kinds of builds: a rolling pre-release of the current state of `main`, and
numbered releases. Today the rolling pre-release is the tag `latest`, moved and edited in place
with every push, and a numbered release is published by hand in the GitHub UI, which creates the
tag and only then starts the build that produces its packages. Three things are wrong with that:

- GitHub shows a release under the date it was first published. The rolling pre-release therefore
  shows the date of its creation, months behind the build it carries, next to a `Pre-release`
  badge and below a numbered release that GitHub marks `Latest`. A reader takes it for an
  abandoned nightly and the project for stale. The text names the real build date, but badges and
  dates are read first.
- The name `latest` collides with GitHub's own alias: `/releases/latest` and
  `/releases/latest/download/<file>` point to the newest numbered release, never to the rolling
  build, while `/releases/tag/latest` and `/releases/download/latest/<file>` point to the rolling
  build.
- A numbered release is public, visible and empty for the length of a CI run before its packages
  arrive, and if the run fails it stays public and empty.

The numbered releases are also to be archived on Zenodo with a DOI, so that a release can be cited
the way the experiment records already cite their data. The archiving must not catch the rolling
pre-release, and it must never produce a record without a description: Zenodo records cannot be
deleted.

## Decisions

- **The rolling pre-release is named `nightly`** and is built at night, not on every push, with a
  manual start for the moments one cannot wait, such as updating the demo host. `nightly` is the
  name every reader knows for the newest automatic build (Neovim, Rust, yt-dlp); the schedule makes
  it true. `dev` was rejected because it is a version suffix, not an established release name, and
  carries no sense of currency; `edge`, `canary` and `continuous` are honest for a build per push
  but less known outside their ecosystems.
- **The nightly is deleted and created anew**, never edited in place. Its GitHub date is then the
  date of its build. This is possible only because Zenodo is not wired to GitHub (next point): the
  `published` event of the new nightly has no listener.
- **A nightly that would carry the same commit as the published one is not built.** The nightly's
  date then names the last change of `main`, and a night without a runner retries itself the next
  night, because `main` still differs from the published nightly.
- **A numbered release is prepared and published by one run of the workflow, with the
  maintainer's approval in between**, from a draft the maintainer wrote. The run builds, tests and
  finishes the draft, and then waits in a GitHub environment that requires approval. The maintainer
  tries the distributions of the draft and approves; only then is anything public. A failed or
  rejected run leaves the draft a draft. Because preparation and publication are one run, what is
  published is what was built and tested, without a second run having to find it again.
- **Zenodo is driven through its API from the workflow**, not through Zenodo's GitHub
  integration. The integration archives every published release including pre-releases, archives
  the moment the maintainer clicks "Publish" with whatever text the release has then, and does not
  take the packages. The API lets the workflow archive after verification, with the release text as
  description, with the packages, and only for versions without a suffix. The workflow creates and
  fills the draft of the record with a token that cannot publish; the token that can is a secret
  of the environment that the approval guards.
- **The version DOI lives in the release text and in the build.** The workflow reserves the DOI
  before it builds and passes it into the build beside `RELEASE_TAG` and `GIT_REVISION`, so that
  `--version` names version, revision and DOI, and an experiment package can read the DOI of the
  build it ran on. A nightly carries no DOI. The version DOI is not written into the tagged source:
  that would need a commit before the tag and a rebuild after it.
- **Only a build made for a release has a version.** The version is the release tag, of the form
  `v<number>`, a suffixed one such as `v1.0.0-rc.1` included. A nightly and a build made by hand
  have none and are named by their revision alone: `--version` prints
  `Evochora v1.0.0 (<revision>)` for a release and `Evochora (revision <revision>)` otherwise. A
  run records the version of its build beside the revision, and the warning for a run read by
  another build names each build by its version where it has one. The DOI is neither part of that
  warning nor recorded with a run: the version finds the release, and the DOI is for citing it.
  This part is built: `BuildInfo`, the field `build_version` in a run's metadata and
  `BuildRevisionCheck`.
- **An archive is named after its tag, and a build without a tag carries none.** The names are
  `evochora-v1.0.0-<platform>` for a release, `evochora-nightly-<platform>` for the nightly, whose
  workflow passes the tag `nightly`, and `evochora-<platform>` for a build made without a tag, by
  hand or for a pull request. The word `latest` appears in no name. A commit hash in the name was
  rejected: the files of the nightly would be named differently every night, and no link, image
  build or deploy could name them. Which commit an archive holds is said by `--version` and by
  the title of the nightly.
- **The concept DOI is entered once by hand**, after the first archived release, as a badge in the
  README, in the `doi` field of `CITATION.cff` and in the homepage. It never changes, so no job
  maintains it. The workflow reads it from `CITATION.cff` to make a later release a version of the
  same record, and refuses a release while the software is archived and the field is empty.
- **`CITATION.cff` names no version.** It says how Evochora is cited; the version someone ran, and
  its DOI, are named by `--version`. A version in the file would have to be set by hand before
  every release, and would be wrong for everyone on a nightly or an older release.
- **A version with a suffix is not archived.** `v1.0.0-rc.1` is published as a pre-release and has
  no DOI.
- **The version number itself is not part of this proposal.** Whether the next release is 0.5.0 or
  1.0.0, and what a major number means for a project that promises no compatibility between
  versions, is a separate decision.

## The nightly

Every push to `main` runs tests, fingerprint and fingerprint comparison, and publishes nothing.

At night GitHub starts the workflow on `main`. The workflow reads the commit the published
nightly names and ends if it is the head of `main`. Otherwise it runs tests, packages for every
platform, the start test of each package, the image and its push to the registry. Only when all of
that has passed does it delete the release and the tag `nightly`, wait briefly, and create both
again: the tag on the built commit, the title `Nightly build <date> (<commit>)`, the text from
`.github/release-notes-nightly.md` with the placeholders filled, the pre-release flag, the
packages. The wait between deletion and creation avoids a known race in which a release created
right after its deletion stays a draft. If anything fails before the deletion, the previous nightly
stands untouched; a broken build never replaces it.

A manual start on `main` runs the same job without the check for changes. The image in the
registry is tagged `nightly`, and the deploy workflow defaults to it.

## A numbered release

1. The maintainer creates a draft release in GitHub: tag name `v<version>`, target `main`, release
   notes. A draft is not public and has no tag yet.
2. The maintainer starts the workflow by hand with the version as input. Without an input the same
   start is a nightly. There is no other way to publish: the triggers on published releases and on
   pushed tags are removed.
3. The workflow checks, before anything is built or reserved: the version has the form
   `v<number>`, there is exactly one draft with that tag name, no release and no tag of that name
   exist, and the text of the draft is not empty. A failed check ends the run with nothing changed
   anywhere.
4. For a version without a suffix, the workflow finds or creates the draft of the record at Zenodo,
   as a new version of the concept record if one exists, and reserves its DOI. A draft an earlier
   run created for the version is used again, so that a restarted run keeps the DOI. A version
   that is archived already is refused.
5. The workflow builds the commit it was started on, the head of `main`, and runs tests,
   fingerprint, packages, start tests and image with that version and DOI as the build's.
6. The workflow finishes the draft: it attaches the packages, sets the draft's target to the built
   commit, and writes a block below the maintainer's text, from a template in the repository, with
   the way to install, the DOI, the commit and a link to the changes since the release before. It
   gives the draft of the record the source archive, the packages, the metadata taken from
   `CITATION.cff`, the version and the text of the release as description. The source archive is
   the one the build packs with the task `sourceArchive`: every file of the commit, and the file
   `GIT_REVISION` that names it, so that a build made from the archive reports that commit. The
   source archive GitHub attaches to a release lacks that file.
7. The run waits for the maintainer's approval. The draft and its packages can be downloaded and
   tried.
8. After the approval the workflow tags the image, publishes the record at Zenodo and publishes
   the draft. GitHub creates the tag at that moment and opens a discussion that announces the
   release. The release appears complete: text, DOI, packages.

Between the Zenodo publication and the GitHub publication lie seconds in which a record exists
whose release is not yet visible. A failure exactly there is repaired by running the failed job
again, which finds the record published and publishes the draft.

A run on another branch than `main`, or one aimed at Zenodo's sandbox, ends when the draft is
prepared and publishes nothing.

## Zenodo

The workflow uses three Zenodo tokens stored as secrets: one for `zenodo.org` that may create and
fill drafts, one for `zenodo.org` that may also publish, held by the environment the approval
guards, and one for `sandbox.zenodo.org`. The calls are in `tools/ci/zenodo_release.py`, which was
developed and tested against the sandbox; sandbox records are not permanent. The first run against
Zenodo itself is the first numbered release, and its publication is irreversible: `CITATION.cff`
must be right before it, authors, ORCID, title, license.

The job uses Zenodo's records API, the one its web interface uses itself, and asks for records in
that API's own format: asked for plain JSON, Zenodo answers in the format of its former deposit
API, which lacks the DOI of a draft. The record is not added to the community `evochora`, whose
records the homepage lists as published experiments.

## Changes

- `.github/workflows/build.yml`: triggers reduced to `schedule`, `workflow_dispatch` with an
  optional version and the choice of the Zenodo system, `push` to `main` and `pull_request`; a
  first job that decides what a run builds and publishes; the publication of the nightly; the
  check of the draft, the reservation of the DOI, the preparation of the release and its
  publication behind the approval.
- `tools/ci/zenodo_release.py`: the calls to Zenodo.
- `.github/release-notes-latest.md` becomes `.github/release-notes-nightly.md`;
  `.github/release-notes-release.md` is the block written below the text of a release.
- `build.gradle.kts`, `infrastructure/docker/evochora-node/Dockerfile`,
  `infrastructure/docker/docker-compose.yml`, `.github/workflows/deploy.yml`, the start test of
  the packages: the fallback and default `latest` are removed, and the archive name carries the
  tag the workflow passes or none, as decided above; the build takes a DOI property beside
  `RELEASE_TAG`.
- `BuildInfo` and the version provider of the CLI: the DOI as a third field, printed by
  `--version` in a line of its own when present.
- `docs/dev/RELEASING.md`: how a release is published, and what is set up once.
- The repository: the environment `release` with the maintainer as required reviewer, open to
  `main` alone, and the three secrets.

## Pitfalls

For the maintainer:

- GitHub's "immutable releases" setting must stay off. Enabled, it protects the tags and locks
  the assets of every new release, and the nightly can no longer be recreated. Releases created
  while it was on stay immutable after it is turned off.
- The token that can publish at Zenodo is reachable only by the job the maintainer has approved.
  Whoever can change the workflow can still create drafts in the maintainer's Zenodo account with
  the other token; a draft is not public and can be discarded.
- GitHub keeps a run waiting for approval for 30 days. After that the run has failed and is
  started again; the draft at Zenodo and its DOI are used again.
- A night without macOS runners costs a day: the nightly retries itself the next night. A
  numbered release whose run fails is restarted by hand; nothing was public.
- The demo host runs a nightly that can be a day behind `main`, unless a nightly is started by
  hand before the deployment.

For users:

- Watchers of releases are notified of every nightly: GitHub does not separate pre-releases
  from releases in its notifications, and has not for years of requests.
- Between deletion and the finished upload of the new nightly, about a minute, the nightly's
  download addresses return an error.
- A clone keeps the old tag `nightly` until tags are fetched with force. The nightly is a
  download, not a git reference, and the documentation names it as one.
- A nightly whose date lies days back means that `main` did not change, not that the project
  stopped. Its title names the commit.
- A DOI belongs to a numbered release, not to a nightly. Who publishes an experiment run on a
  nightly cites its commit; the user guide says that a run meant for publication is made on a
  numbered release, or that the package carries the build.

## Rejected alternatives

- **Updating the rolling release in place**, as it was done before: keeps the stale
  GitHub date, and that date is what readers see first. The date in the title mitigates it; the
  recreation removes it.
- **Zenodo's GitHub integration**: no filter for pre-releases, archives at the click, no packages,
  no check of the description. It would also forbid recreating the nightly.
- **A separate repository for the nightly**, as yt-dlp keeps it: solves the date as well, but a
  second place a newcomer has to know, for a problem the recreation solves in the same place.
- **Writing the version DOI into the tagged source**: needs a commit by the workflow before the
  tag and a build after it; the release text and the build carry the DOI instead.
- **A version in `CITATION.cff`**, kept by hand and checked by the workflow, or written by the
  workflow: a step before every release, or bot commits on `main`, for a field that is wrong for
  everyone who runs another build than the latest release. Of nine comparable projects with a
  `CITATION.cff`, seven name no version.
- **Publishing without a halt**, as the last step of the run: the maintainer could not try the
  packages of a release before it was public, and a Zenodo record cannot be taken back.
- **Two workflows, one that prepares and one that publishes**: the second would have to find what
  the first prepared, the image among it, and establish that it is still the same. One run that
  waits for approval publishes what it built by construction.
- **Naming the Docker image in the text of a release**: the image serves the demo host, and
  software that ships its own Java runtime does not usually advertise one.

## Outcome

Built as described above. Checked before the merge: the names of the archives and what
`--version` prints, by building; the decisions of the first job, the check of the draft and the
writing of the release text, by running the scripts of the workflow against a stand-in for
GitHub; `tools/ci/zenodo_release.py`, against Zenodo's sandbox, through reserving, filling,
publishing, a second version of the same record and every refusal.

Left to the first runs on `main`, because a schedule and the publication run only there: the
nightly at its time, its deletion and recreation, the image tagged `nightly`; and for the first
numbered release, the attachment of the packages to a draft, the approval, the token for
`zenodo.org` that may not publish, and the discussion GitHub opens. After the first nightly the
release `latest` and its tag are deleted with `gh release delete latest --cleanup-tag --yes`.
After the first archived release the concept DOI is entered as `docs/dev/RELEASING.md` describes.

Not decided here: the version number of the next release and what the major number promises; and
whether a failed nightly is restarted automatically after a delay, should runner shortages become
frequent.
