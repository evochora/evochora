# Proposals

A decision with scope is recorded as a proposal. Its folder states its status:

| Folder | Status |
|---|---|
| `idea/` | Not decided. Not a specification and no commitment to build it. |
| `draft/` | Being worked out or built. Exists only on a working branch, never on `main`. |
| `done/` | Implemented as described; small deviations are allowed, missing parts are not. |
| `declined/` | Not implemented; where there is a reason worth keeping, the proposal states it. Not a ban: whoever takes the topic up again writes a new proposal. |

A proposal is written in `draft/` on its working branch and moved to `done/`, `declined/` or
`idea/` before the pull request is merged.

A proposal refers to another proposal only when both are parts of one larger plan; it does not
cite other proposals as background.

A proposal in `done/` or `declined/` is not edited again. Its links may therefore point to files
that have since moved.
