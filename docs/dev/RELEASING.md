# Releasing

This describes how Evochora is released: the nightly build, a numbered release, what follows the
first archived one, and what has to be set up once. Everything is done by the workflow
`.github/workflows/build.yml`.

## The nightly

The pre-release `nightly` is the current state of `main`. A push to `main` is tested and publishes
nothing. At night, at the time the schedule of the workflow names, the workflow builds `main` if
it has changed since the published nightly, and publishes the result only after tests, packages,
start tests and the image have all passed. It deletes the release and the tag `nightly` and creates
both anew, so that GitHub shows the nightly under the date of its build; its title names that date
and the commit. For about a minute in between there is no nightly to download. Nobody edits it by
hand.

To get a nightly now, for example before updating the demo host:

```
gh workflow run build.yml --ref main
```

The run takes as long as a full CI run and builds `main` whether or not it has changed. The image
is then at `ghcr.io/evochora/evochora:nightly`.

Treat the tag `nightly` as a download address, never as a git reference: it is recreated with
every build, and a clone keeps the old one.

GitHub notifies everyone who watches the releases of the repository of every nightly; it does not
tell pre-releases from releases there. It also switches the schedule off when the repository has
seen no activity for 60 days. `gh workflow enable build.yml` switches it on again.

## A numbered release

A release is prepared by one run of the workflow and published by the same run, after you have
approved it. Until then nothing is public.

1. In GitHub, create a **draft** release: tag name `v<version>` (a new tag, GitHub creates it on
   publication), target `main`, and the release notes. Do not publish it.
2. Start the workflow with the version:

   ```
   gh workflow run build.yml --ref main -f version=v<version>
   ```

3. The workflow checks that there is exactly one draft for the version, that no release and no tag
   of that name exist, and that the draft has a text. It then creates the draft of a record at
   Zenodo and reserves its DOI, builds and tests everything with the version and the DOI, attaches
   the distributions to the draft, aims the draft at the commit it built, and writes a block below
   your text, from `.github/release-notes-release.md`: how to install, the DOI, the commit, and a
   link to the changes since the release before. The draft at Zenodo gets the sources, the
   distributions, the text of the release and a README that says what its files are, from
   `.github/zenodo-readme.md`, which Zenodo shows first.
4. The run now waits at the job "Publish Release". Try the release: download a distribution from
   the draft, start it, and check that `bin/evochora --version` names the version and the DOI.
5. Approve the job on the page of the run, under "Review deployments". The workflow tags the image,
   publishes the record at Zenodo, and publishes the draft as its last step. GitHub creates the tag
   at that moment and opens a discussion in "Announcements".

GitHub keeps a run waiting for 30 days. After that the run has failed, and you start it again.

The block below your text begins with a comment line. What stands above that line is yours and is
kept by every run; what stands below is replaced. The record at Zenodo has the text as it was when
the run prepared the release: if you change the text of the draft afterwards, start the workflow
again, so that both say the same.

A version with a suffix, such as `v1.0.0-rc.1`, goes the same way, is published as a pre-release
and is not archived on Zenodo: it has no DOI.

### When a release is not approved

Reject the job instead of approving it, or let a failed run stand. The draft in GitHub stays a
draft, and the record at Zenodo stays a draft. Fix what was wrong and start the workflow again with
the same version: it uses the same draft at Zenodo, so the DOI stays the same, and replaces the
files and the text of both drafts.

To give a version up, delete the draft in GitHub and discard the draft at Zenodo, under "My
dashboard" there. Its DOI was never registered and is gone with it.

If a run fails after the record at Zenodo has been published and before the draft in GitHub has,
re-run the failed job of that run. It finds the record published and publishes the draft. A new run
for the same version would be refused, because the version is archived already.

### Trying the workflow

```
gh workflow run build.yml --ref main -f version=v<version> -f zenodo=sandbox
```

A run started with `zenodo=sandbox` works with `sandbox.zenodo.org`, whose records and DOIs are for
testing, and ends when the draft is prepared: it publishes nothing. The same holds for a run on
another branch than `main`. The draft in GitHub then names a DOI of the sandbox, and its
distributions are built with it; a later run without `zenodo=sandbox` replaces all of that.

## After the first archived release

Zenodo gives the software a DOI that stands for all its versions, beside the DOI of each version.
It is shown on the page of the record, under "Cite all versions". Enter it once, in a commit
without a release:

- as the `doi` field of `CITATION.cff`,
- as the badge in `README.md`, which waits there in a comment,
- as `ZENODO_CONCEPT_RECORD` in `infrastructure/demo/site/js/model/Download.js`, by its number
  alone, so that the homepage shows it.

From then on the workflow makes every release a new version of that record. It finds the record by
the `doi` field of `CITATION.cff` and refuses a release while the software is archived and the
field is empty.

`CITATION.cff` names no version. It says how Evochora is cited; which version someone ran is said
by `bin/evochora --version`, with the DOI of that version.

## What is set up once

The repository has an environment named `release`, under "Settings", "Environments": it requires
the approval of the maintainer, allows the maintainer to approve a run of their own, and is open to
`main` alone. The job that publishes a release runs in it. The workflow refuses to publish when the
environment requires no approval.

Three tokens of Zenodo are stored as secrets. A token is created under "Applications" in the
settings of the Zenodo account, and the records belong to the account that created the token.

| Secret | Where | Token of | Scopes |
|---|---|---|---|
| `ZENODO_TOKEN` | the repository | zenodo.org | `deposit:write` |
| `ZENODO_PUBLISH_TOKEN` | the environment `release` | zenodo.org | `deposit:write`, `deposit:actions` |
| `ZENODO_SANDBOX_TOKEN` | the repository | sandbox.zenodo.org | `deposit:write`, `deposit:actions` |

The first token creates and fills drafts and cannot publish. The second can, and a run reaches it
only after the approval.

## Never

- Enable GitHub's "immutable releases" setting: the nightly could not be recreated, and releases
  created while it was on stay immutable.
- Push a `v*` tag by hand or publish a draft by hand: the release would be public without its
  record at Zenodo, and with the image untagged.
- Expect a published Zenodo record to be deletable: it is not. The approval exists for that.
- Add the record of the software to the Zenodo community `evochora`: the homepage lists the records
  of that community as published experiments.
