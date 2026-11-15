# Release Process

**Status: DRAFT — decided in conversation, not built. Builds on the publish job of the branch
`release-prep`, which already writes the rolling pre-release from a template and marks suffixed
versions as pre-releases; the changes below are made on top of that job.**

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
- **A numbered release is published by the workflow as its last step**, from a draft the
  maintainer wrote. Nothing is public before every package exists and every test has passed; a
  failed run leaves the draft a draft.
- **Zenodo is driven through its API from the workflow**, not through Zenodo's GitHub
  integration. The integration archives every published release including pre-releases, archives
  the moment the maintainer clicks "Publish" with whatever text the release has then, and does not
  take the packages. The API lets the workflow archive after verification, with the release text as
  description, with the packages, and only for versions without a suffix.
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
  the title of the nightly. This part is not built: it changes the names the workflow, the image
  and the start test look for.
- **The concept DOI is entered once by hand**, after the first archived release, as a badge in the
  README and in the `doi` field of `CITATION.cff`. It never changes, so no job maintains it.
- **`CITATION.cff` carries `version` and `date-released`, kept by hand**, and the workflow checks
  before it builds that `version` equals the version it was started with, and, once a concept DOI
  exists, that `doi` names it. `date-released` is not checked, so that a release that slips a day
  does not fail on its date.
- **The version number itself is not part of this proposal.** Whether the next release is 0.5.0 or
  1.0.0, and what a major number means for a project that promises no compatibility between
  versions, is a separate decision.

## The nightly

Every push to `main` runs tests, fingerprint and fingerprint comparison, as today, and publishes
nothing.

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

1. The maintainer brings `CITATION.cff` to the version and date of the release, and creates a
   draft release in GitHub: tag name `v<version>`, target `main`, release notes. A draft is not
   public and has no tag yet.
2. The maintainer starts the workflow by hand with the version as input. Without an input the same
   start is a nightly. There is no other way to publish: the triggers on published releases and on
   pushed tags are removed.
3. The workflow finds the draft by its tag name and checks, before anything is built or reserved:
   the draft exists, its text is not empty, the tag has the form `v<number>`, `CITATION.cff` names
   the version and, once there is one, the concept DOI. A failed check ends the run with nothing
   changed anywhere.
4. The workflow fixes the commit it builds, the head of `main` at the start, and runs tests,
   fingerprint, packages, start tests and image with that version as the build's version.
5. For a version without a suffix: the workflow reserves the DOI at Zenodo, writes it into the
   draft's text and passes it into the build; after the build it creates the record, as a new
   version of the concept record if one exists, uploads the source archive, the packages and the
   metadata taken from `CITATION.cff`, sets the version and the release text as description, and
   publishes the record. The source archive is the one the build packs with the task
   `sourceArchive`: every file of the commit, and the file `GIT_REVISION` that names it, so that a
   build made from the archive reports that commit. The source archive GitHub attaches to a
   release lacks that file. Before it creates anything it checks whether a record for this version
   already exists, so that a restarted run after a failure between Zenodo and GitHub does not
   create a second version. A version with a suffix, `v1.0.0-rc.1`, skips Zenodo and is published
   as a pre-release.
6. The workflow sets the draft's target to the built commit, attaches the packages and publishes
   the draft. GitHub creates the tag at that moment. The release appears complete: text, DOI,
   packages.

Between the Zenodo publication and the GitHub publication lie seconds in which a record exists
whose release is not yet visible. A failure exactly there is repaired by a restart, which finds the
record and publishes the draft without a second deposit.

## Zenodo

The workflow uses a Zenodo token stored as a repository secret, and a second token for
`sandbox.zenodo.org`. The job is developed and tested against the sandbox with a throwaway draft
in the real repository, which is deleted afterwards; sandbox records are not permanent. The first
run against Zenodo itself is the first numbered release, and it is irreversible: `CITATION.cff`
must be right before it, authors, ORCID, title, license.

The id of the concept record is kept in the repository once it exists, so that later releases
become versions of it. Zenodo supports both its legacy deposit API and the newer InvenioRDM API;
which one the job uses is decided when it is written.

## Changes

- `.github/workflows/build.yml`: triggers reduced to `schedule`, `workflow_dispatch` with an
  optional version input, `push` to `main` and `pull_request`; the publish job as described, the
  Zenodo steps, the checks; the step that removes stale assets of the rolling release becomes
  unnecessary.
- `.github/release-notes-latest.md` becomes `.github/release-notes-nightly.md`.
- `build.gradle.kts`, `infrastructure/docker/evochora-node/Dockerfile`,
  `.github/workflows/deploy.yml`, the start test of the packages: the fallback and default
  `latest` are removed, and the archive name carries the tag the workflow passes or none, as
  decided above; the build takes a DOI property beside `RELEASE_TAG` and `GIT_REVISION`.
- `README.md` and `docs/USER_GUIDE.md`: the archive names in the Quick Start and in "Install and
  Start".
- `BuildInfo` and the version provider of the CLI: the DOI as a third field, printed by
  `--version` when present.
- `CITATION.cff`: `version`, `date-released`, later `doi`.
- `docs/dev/RELEASING.md`: how a release is published, written with this proposal.
- `README.md`: the concept DOI badge, after the first archived release; a sentence that the
  nightly is a pre-release that watchers of releases will be notified of.

## Order of execution

1. The publish job of `release-prep` is on `main`.
2. The workflow changes, the renamed template and the defaults are built and merged. The first
   nightly is started by hand.
3. The release `latest` and its tag are deleted:
   `gh release delete latest --cleanup-tag --yes`.
4. `CITATION.cff` with the maintainer's ORCID is on `main`.
5. The Zenodo job is tested against the sandbox.
6. The first numbered release is published through the workflow.
7. The concept DOI goes into the README and `CITATION.cff` in a commit without a release.

## Pitfalls

For the maintainer:

- GitHub's "immutable releases" setting must stay off. Enabled, it protects the tags and locks
  the assets of every new release, and the nightly can no longer be recreated. Releases created
  while it was on stay immutable after it is turned off.
- The Zenodo token lets whoever can change the workflow publish permanent records in the
  maintainer's name. As long as the maintainer alone pushes to `main` that is acceptable; the day
  others get write access, the token belongs in an environment with a required approval.
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

- **Updating the rolling release in place**, as today and on `release-prep`: keeps the stale
  GitHub date, and that date is what readers see first. The date in the title mitigates it; the
  recreation removes it.
- **Zenodo's GitHub integration**: no filter for pre-releases, archives at the click, no packages,
  no check of the description. It would also forbid recreating the nightly.
- **A separate repository for the nightly**, as yt-dlp keeps it: solves the date as well, but a
  second place a newcomer has to know, for a problem the recreation solves in the same place.
- **Writing the version DOI into the tagged source**: needs a commit by the workflow before the
  tag and a build after it; the release text and the build carry the DOI instead.
- **Letting the workflow write `version` and `date-released` into `CITATION.cff`**: bot commits
  on `main` for two fields that a check enforces as well.

## Open

- Whether release candidates, `v1.0.0-rc.1`, also get a DOI. The proposal excludes them; they
  are real versions and Zenodo would accept them.
- Which Zenodo API the job uses.
- Whether a failed nightly is restarted automatically after a delay, should runner shortages
  become frequent.
- The version number of the next release and what the major number promises.
