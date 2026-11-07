# Releasing

This describes the release process that the proposal
[Release Process](../proposals/draft/RELEASE_PROCESS.md) introduces. Until that proposal is built,
the workflow still publishes the rolling pre-release `latest` on every push and builds a numbered
release after it was published by hand.

## The nightly

The pre-release `nightly` is the current state of `main`, built every night when `main` has
changed since the last nightly, and published only after tests, packages, start tests and the
image have all passed. Its title names the build date and the commit. Nobody edits it by hand.

To get a nightly now, for example before updating the demo host:

```
gh workflow run build.yml --ref main
```

The run takes as long as a full CI run. The image is then at `ghcr.io/evochora/evochora:nightly`.

Treat the tag `nightly` as a download address, never as a git reference: it is recreated with
every build, and a clone keeps the old one.

## A numbered release

1. Set `version` and `date-released` in `CITATION.cff` to the release and merge that to `main`.
   The workflow refuses a version that the file does not name.
2. In GitHub, create a **draft** release: tag name `v<version>` (a new tag, GitHub creates it on
   publication), target `main`, and the release notes. Do not publish it.
3. Start the workflow with the version:

   ```
   gh workflow run build.yml --ref main -f version=v<version>
   ```

4. The workflow checks the draft, its text, the tag name and `CITATION.cff`, builds and tests
   everything, archives the release on Zenodo with a reserved DOI, and publishes the draft as its
   last step, with the packages attached and the DOI in the text. Nothing is public before that
   step.

If the run fails, the draft is still a draft. Fix the cause and start the workflow again; a
restart after a failure between the Zenodo and the GitHub publication finds the existing record
and does not create a second one.

A version with a suffix, such as `v1.0.0-rc.1`, goes the same way, is published as a pre-release
and is not archived on Zenodo.

## After the first archived release

Zenodo gives the software a concept DOI that stays the same over all versions. Enter it once as a
badge in the README and in the `doi` field of `CITATION.cff`, in a commit without a release. The
workflow checks the field from then on.

## Never

- Enable GitHub's "immutable releases" setting: the nightly could not be recreated, and releases
  created while it was on stay immutable.
- Push a `v*` tag by hand or publish a draft by hand: the workflow publishes, after it has built.
- Expect a Zenodo record to be deletable: it is not. The checks before the build exist for that.
