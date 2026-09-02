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
- Interface changes: analyze full impact (all implementations, all call sites, all tests)
- Multi-file changes: present the plan and get explicit approval before writing code
- Never silently rewrite working code - explain what and why first

**During Implementation:**
- Any deviation from the agreed plan - one more file, a changed order, a finding taken along, a step that turns out infeasible - stops the work: state what was found, why a deviation is needed and what it would look like, then wait for the answer. Never start while asking
- Anything unexpected - a test failing or passing against expectation, a build error, a number off the prediction - stops the work the same way

## Source Code Comments

- Comments in the source code must never be a reaction to a previous conversation
- All comments need to be understandable without the context of previous conversations
- Never comment what changed, but only explain the current code
