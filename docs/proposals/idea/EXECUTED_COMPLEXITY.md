# A Complexity Measure from Executed Code

**Status: IDEA — not decided. Open questions at the end.**

A measure of how complex an organism's program is, read from the code it executes rather than from
the cells it owns. It would supply the fourth MODES measure, complexity, which experiment records do
not report today, and it would answer the question every record leaves open: whether any organism
became more complex.

## Problem

MODES (Dolson, Vostinar, Wiser & Ofria, *Artificial Life* 25(1), 2019) measures change, novelty,
ecology and complexity of the lineages that persist. The first three need only descent and
abundance; complexity needs a measure per organism, and MODES leaves that measure to the system.
Evochora has none. The analytics and the organisms table hold no property of a body; the bodies
themselves are in the raw batches at every recorded tick.

The obvious candidates fail in known ways:

- **Size of the body or number of code cells** grows with every duplication, whether or not the
  copy ever runs or does anything.
- **Number of distinct instructions in the body** is bounded by the ISA and counts code that never
  executes.
- **Information content across the population** (Adami, Ofria & Collier, *PNAS* 97(9), 2000) needs
  an alignment of bodies, which programs of varying length and layout do not offer without a project
  of its own.

## Idea

Count what an organism executes until it repeats itself. A loop counts once however often it runs;
every additional branch, routine or behaviour adds to the count; code that never runs adds nothing.

**The executed control-flow graph.** Over one reproduction cycle, from an organism's birth to its
first child: nodes are the positions of its body that were executed (relative to its origin), edges
the transitions observed between them. Two numbers describe it: the number of executed positions,
and the cyclomatic number, edges − nodes + 2 (McCabe, *IEEE TSE* 2(4), 1976), which counts the
independent paths and ignores how often a loop runs. Neither is bounded by the ISA, only by the
body, and the body can grow. A duplicated region that never runs does not count; one that runs adds
positions but hardly any paths.

**The same graph, read statically.** Instead of the execution, the body: the graph of the positions
reachable from the entry point, following jumps to labels with the same tolerant matching the virtual
machine uses. It needs only bodies, which a record package holds at every kept block, so it covers
every carrier of every persistent component at every kept tick. Reachable is not executed — a branch
never taken counts — so the static graph is an upper bound of the dynamic one.

**A third reading**, closer to the wording "until it repeats", is the compressibility of the
instruction sequence executed over a cycle (Lempel-Ziv complexity, Lempel & Ziv, *IEEE TIT* 22(1),
1976). It depends on how long the sequence is and how often waiting loops run, so an organism that
waits longer for energy would read as different without being more complex. It is a cross-check, not
the measure.

The likely shape: the static graph as the measure, computed for MODES at the kept blocks; the dynamic
graph from the trace recorder for a sample of organisms, as the check of how far the static bound
lies above what actually runs.

## What exists and what is missing

- **Bodies**: in the raw batches at every recorded tick, in a record package at the kept blocks.
- **Executions**: the trace recorder replays a window of a recorded run and writes every step with
  its position relative to the origin, which is the dynamic graph's input. Its cost grows with the
  size of the world and the length of the window; a reproduction cycle is long, so the dynamic graph
  is a sample, not a census.
- **Missing**: the static graph — reading a body, resolving its jumps as the machine would, and
  walking from the entry point — exists nowhere and would be new code, best placed next to the
  disassembler.

## Open questions

- **Tolerant jumps.** A jump is resolved when it executes, by fuzzy label matching
  ([Assembly Spec](../../ASSEMBLY_SPEC.md#fuzzy-label-matching)): the organism's own labels first,
  those differing in the fewest bits, one of several equal ones chosen at random; a foreign label
  only when none of its own matches. A static graph can follow the own labels and must give a jump
  with tied targets an edge to each; a jump that matches no own label depends on the neighbourhood,
  and `JMPR` and `JMPS` take their target from a register or the stack. How much of a body these
  cases cover decides how well the static graph can be built.
- **Code that changes while it runs.** An organism that writes into its own body, or a neighbour
  that writes into it, makes both graphs blurrier; how often this happens is unmeasured.
- **What the number means.** A larger graph can be more function or more detour. Bedau's statistics
  compare with a neutral shadow; a shadow has no bodies, so complexity has no such baseline. Whether
  a comparison against the ancestor, against non-persistent relatives or against random
  rearrangements of the same body can serve as one is open.
- **Where it belongs.** A function in the `analyze-run` script, an analytics plugin over the bodies
  at every recorded tick, or both; a plugin would make it visible in the Analyzer for every run but
  sees one recording at a time, which the static graph allows and the dynamic one does not.
