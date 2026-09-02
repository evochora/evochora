# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

For project overview, architecture, build commands, testing, logging, and documentation guidelines see **[AGENTS.md](AGENTS.md)**.

For architectural review criteria see **[.agents/architecture-guidelines.md](.agents/architecture-guidelines.md)**.

## Change Management

**Before Proposing Solutions:**
- Verify the solution is fundamentally possible given system constraints
- For architectural changes: confirm the approach solves the problem without creating new ones
- For performance claims (heap, CPU, latency): verify with evidence or explicitly state uncertainty

**Before Implementing Changes:**
- Every step gets a plan and explicit approval before code is written, however clear the proposal seems
- Interface changes: analyze full impact (all implementations, all call sites, all tests)
- Never silently rewrite working code - explain what and why first

**During Implementation:**
- Any deviation from the agreed plan - one more file, a changed order, a finding taken along, a step that turns out infeasible - stops the work: state what was found, why a deviation is needed and what it would look like, then wait for the answer. Never start while asking
- Anything unexpected - a test failing or passing against expectation, a build error, a number off the prediction - stops the work the same way
- Approval of a plan does not cover what arrives later: review comments, defects found on the way and follow-ups each need their own go-ahead

## Communication

- One topic per message: gather first, then raise exactly one point and wait. Everything else goes into an ordered backlog file in the session's scratchpad, never into the memory directory, and comes up one by one. The agent owns the order and finishes the current point before opening the next
- Review points, trivial ones included: explain what the point says and whether it holds in the code, give a recommendation, wait for the decision, then act
- Every outward or persistent action - creating issues, writing documents, messaging other sessions - is a change and needs approval; agreement between two agent sessions never replaces the maintainer's
- No messages to other agent sessions unless the maintainer asks for it
- A suggestion stays a suggestion until the maintainer explicitly decides; never record it as decided
- Delegate mechanical work to cheaper models, keep judgement work, and verify delegated results
- Every point, plan step or option is presented so the maintainer can decide without reading the code: what it is, what changes for the simulation, experiments or data, who is affected, and only then the mechanism. A plan step names the files, the concrete change, the verification command and the finished state
- Options come with their consequences - what gets slower, stricter, or lands on the maintainer's desk; an estimate is marked as an estimate, with its basis
- Answer every question in a message, not only the first
- Relay a review completely: every finding, each read in full and understandable on its own, with verification against the code and a recommendation

## Source Code Comments

- Comments in the source code must never be a reaction to a previous conversation
- All comments need to be understandable without the context of previous conversations
- Never comment what changed, but only explain the current code
