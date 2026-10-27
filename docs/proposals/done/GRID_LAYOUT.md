# Tiled Grid Layout

**Status: ACCOMPLISHED 2026-09-03 — implemented on branch `feature/grid-layout`; outcome recorded at the end.**

## Problem

The environment stores its cells in one flat `int[]` per attribute (molecule, owner) in row-major
order with the last dimension contiguous. Organisms execute along their direction vector, and the
default direction is +X, dimension 0. In the production world of 7680×4320 cells a step along X
therefore moves 4320 ints, 17 KB, through memory: every instruction fetch, every skip over a NOP
cell and every argument read lands on a different cache line and a different memory page. The grid
is 133 MB, far beyond any cache.

The CPU profile of a production run (2026-08-30, ~900 organisms) attributed 11.8 % of the engine to
`skipNopCells`, almost entirely memory stalls, and further stalls to the instruction fetch itself.

### Evidence

A throw-away experiment (branch `experiment/grid-layout-flip`, to be deleted) made dimension 0
contiguous and left everything else untouched. That is the weakest possible layout change: it helps
the walk along X and does nothing for the extent of a body in Y.

| Measurement | main | experiment |
|---|---|---|
| Demo server, ARM, P=4, sparse profile, 10 M ticks, wall-clock | 437 s / 436 s | 401 s / 410 s |
| Same runs, normalized to organism-ticks actually simulated | 0.79 s per M | 0.67 s per M |
| Local x86, P=1, share of `skipNopCells` in engine samples | 7.0 % | 2.8 % |
| Detailed profile, 100k ticks, every tick serialized | 38 s / 38 s | 38 s / 38 s |

The normalization is needed because the experiment changed the order in which randomness is
consumed and therefore the trajectory; the trajectories were shown to lie within the spread of four
seeds. The tick hash of each variant was bit-identical between ARM and x86 and between one and four
threads. The persisted format did not change and the serialization cost did not measurably grow.

A tiled layout, in which a square block of cells is contiguous, extends the gain to both directions
of a body and to n dimensions. This document specifies that layout, and it specifies it in a way
that makes the layout unobservable: no simulation result may depend on it.

## Decisions taken

These were agreed on 2026-09-02 and are not re-opened here.

1. The runtime keeps a layout index, a cell numbering that differs from the flat index in which
   cells are persisted. The flat index of `EnvironmentProperties` remains the persisted contract; the pipeline, resume, CLI and
   visualizer are untouched.
2. Tiles are cubic blocks with side **t = 32** in every dimension. `t` is a constant of the runtime,
   not a configuration key. Every world dimension must be a multiple of 32; a world that is not is
   rejected at startup.
3. The simulation stays n-dimensional. The layout is defined for any n; the world minimum of 32ⁿ
   cells is accepted (2D and 3D are the targets, 4D is conceivable, more is not planned).
4. Results are independent of the layout. Every place where the order of cells enters a decision is
   defined over the flat index, which is a pure function of the coordinate.
5. No performance change buys an architectural regression. Layout knowledge lives in exactly one
   class; the change removes the duplicated stride and wrap arithmetic that exists today.

## Design

### The layout as one class

`GridLayout`, a final class in `org.evochora.runtime.model` next to `Environment` and
`EnvironmentProperties`, owns the cell addressing. It has no interface and one implementation, so
the JIT sees plain array and integer arithmetic on the hot path. It is constructed from the world
shape, the topology and the tile side; production always passes 32, tests pass other values to
prove invariance. The name says what the class is, not how it works, so it survives a later
variable tile side.

Its primitives are exactly what the engine needs and nothing more. The layout names each primitive after what it produces,
because the class name gives the context; `Environment` exposes them under the descriptive names
its existing index API already uses (`getCoordinateFromIndex`, `getMoleculeInt`):

| `GridLayout` | `Environment` | Purpose | Cost |
|---|---|---|---|
| `layoutIndex(coord)` | `getIndexFromCoordinate(coord)` | coordinate → layout index, coordinate already in range | shifts, masks, one multiply-add per dimension |
| `coordinate(layoutIndex, out)` | `getCoordinateFromIndex(layoutIndex, out)`, next to the existing allocating overload | layout index → coordinate, allocation-free | shifts for the offset inside the tile, one division per dimension for the tile position |
| `step(layoutIndex, dim, forward)` | `stepIndex(layoutIndex, dim, forward)` | layout index of the neighbouring cell along one dimension, honouring tile boundaries and the topology; `-1` outside a bounded world | shift and mask; a division only when a tile boundary is crossed |
| `flatIndex(layoutIndex)` | `toFlatIndex(layoutIndex)` | layout index → flat index | decode plus multiply-add |

### Index arithmetic

For n dimensions, shape `S`, tile side `t = 32 = 2^5` and tile counts `T_i = S_i / 32`:

```
tile position     q_i = c_i >> 5          offset in tile  o_i = c_i & 31
tile number       Q   = Σ q_i · TS_i      with TS_0 = 1, TS_i = TS_(i-1) · T_(i-1)
offset            O   = Σ o_i << (5·i)
index             I   = (Q << (5·n)) + O
```

Dimension 0 is the fastest both between tiles and inside a tile, so a step along the default
direction of execution moves to the neighbouring array element until the tile edge. A tile holds
32ⁿ cells: 1024 in 2D, one 4 KB page. The decode of the tile number needs one division per
dimension, the same count as today's row-major decode; the offset needs none.

A step along dimension `d` in direction `s`:

```
o = (I >> (5·d)) & 31
inside the tile      (s > 0 and o < 31) or (s < 0 and o > 0):   I + s · (1 << (5·d))
crossing to the next tile:                                          I + s · TS_d · 32ⁿ  −  s · 31 · (1 << (5·d))
crossing the world edge (toroidal): the tile position wraps to 0 or T_d − 1
crossing the world edge (bounded):  −1
```

The tile position along `d` is needed only when a tile boundary is crossed, once every 32 steps.

### What the layout replaces

Today the layout is known, explicitly or implicitly, in eight places. All of them move onto the
layout class or onto the environment's coordinate-based API:

| Today | After |
|---|---|
| `Environment` computes row-major strides | delegates to the layout |
| `Organism.skipNopCells` and `getRawArgumentsFromEnvironment` compute base index + position × stride and duplicate the toroidal wrap | `index = step(index, dim, sign)` |
| `VirtualMachine` computes the fetch index from `EnvironmentProperties` strides | `environment.getMoleculeIntAt(ip)` |
| `PreExpandedHammingStrategy` decodes a label's coordinate with `EnvironmentProperties` strides | unchanged: labels are keyed by the flat index, whose strides are those of `EnvironmentProperties` |
| `GeneInsertionPlugin`, `GeneDuplicationPlugin` compute their own row-major strides and index cells with them | environment conversions, own strides removed |
| `GeneDeletionPlugin` decodes environment indices through `EnvironmentProperties` | environment conversion |
| `Simulation.resolveConflicts` keys contenders by the flat index of a coordinate | unchanged: any injective key is correct, and a layout-index key would need an index-returning method outside the model package |
| `SeedEnergyCreator` draws a random layout index | draws a random flat index and converts it (see next section) |

After the change, `EnvironmentProperties` strides are used outside the environment only where
flat indices are produced or decoded: the label strategy's distance computation, the restorer,
and the plugins' conversions between coordinates and flat indices; the conversion of layout
indices to flat indices happens inside the environment. Everything else in the runtime treats an
index as an opaque number that only the environment can interpret. That rule enters `AGENTS.md` for plugin
authors; three of the project's own plugins violated it silently.

### Layout independence

The experiment changed trajectories because four decisions consume randomness or break ties in
index order. Each of them is redefined over the flat index, which depends only on the
coordinate. None of them is on the per-instruction path, so the redefinition costs nothing
measurable.

| Site | Today | After |
|---|---|---|
| `Environment.visitCellsOwnedBy` — the order in which mutation operators visit a child's cells and draw randomness | ascending layout index | ascending flat index |
| Label index candidate lists — order of weighted reservoir sampling; tie-break for equal score and owner | sorted by layout index | sorted by flat index |
| `SeedEnergyCreator` — the cell a random number selects | `nextInt(totalCells)` as layout index | as flat index, converted |
| `DeathContext` — the order death handlers visit a dying organism's cells | hash-set order of layout indices | ascending flat index |

The gap search of `GeneInsertionPlugin` needs no change: it sorts the coordinates of owned cells
along the direction vector and breaks ties on those coordinates, so it is layout-independent
already. Its only order dependence is the visiting order of the child's cells, the first row of
the table.

`DeathContext` is included although today's only death handler is order-independent: hash-set
order also differs between a live environment and one rebuilt from a snapshot, so the current
contract leaves a resume-neutrality trap for any future handler.

With these definitions the tick hash of a run is the same for every tile side, including `t = 1`,
which is exactly today's row-major layout. Two consequences:

- Today's reference hashes remain valid. The change is behaviour-preserving in the strict sense the
  project uses for determinism.
- Invariance is testable: the same scenario run with `t = 1` and `t = 32` must produce identical
  states. Any remaining dependency on the layout shows up as a hash mismatch and names itself.

### Persisted contract

`CellDataColumns.flat_indices` keeps its meaning: the row-major index of `EnvironmentProperties`.
The encoder converts each occupied or changed cell on the way out, at sampling ticks only. The
restorer decodes each flat index through `EnvironmentProperties`, not through the environment's
own numbering. Old runs stay readable and resumable; a run may be resumed under a different tile
side and stays bit-identical, because the tile side is a runtime property with no observable effect.

### Validation

At construction the environment rejects a shape with a dimension that is not a multiple of 32. The
message names the dimension and the two nearest valid values:

```
World dimension 1 is 3000, which is not a multiple of 32; the nearest valid sizes are 2976 and 3008.
```

The `shape` entry of `reference.conf` states the rule where a user sets the world. No other
configuration changes.

### Hot-path cost

- Fetch and argument reads: the same number of operations as today, with shifts and masks replacing
  the stride multiplication.
- Skip loop: one shift, one mask and one comparison per step instead of one multiply-add; every
  32nd step additionally one division and one comparison for the tile position. Today's toroidal
  wrap comparison disappears from the loop into `step`.
- Label distance: identical division count to today.
- Encoder: one conversion per serialized cell at sampling ticks; not measurable in the detailed
  profile of the experiment.
- Allocation: none on any of these paths. The layout holds only final `int` fields and one `int[]`
  of tile strides.

## Implementation plan

The plan is ordered so that every slice ends at an oracle that can fail, and so that the first
slices are pure refactorings whose oracle is "the reference hash of main is unchanged". The layout
becomes visible only after the code has proven that nothing can observe it.

### Slice 1 — the layout class

The class with all four primitives and a parameter for the tile side. Unit tests in two, three and
four dimensions: round trips between coordinate and index for every cell of small worlds; the set of
flat indices of all layout indices is a permutation; `step` agrees with coordinate arithmetic
for every cell and every direction, at tile edges, at world edges, toroidal and bounded. `t = 1`
must reproduce today's row-major numbering exactly.

Nothing is wired. Failure here is an arithmetic bug and is found by the cheapest test there is.

### Slice 2 — layout-independent orderings

The four sites of the invariance table are redefined over the flat index while the environment
still uses the row-major numbering, under which flat and layout index coincide. This slice
changes no behaviour at all.

Oracle: the full test suite, and the 10 M-tick reference run on the demo server produces the same
tick hash as main. A different hash means a site was mis-translated.

### Slice 3 — wiring and the invariance test

The environment delegates all index arithmetic to the layout; the eight sites of the replacement
table move onto the environment's API; the encoder and restorer convert. Production still constructs
the layout with `t = 1`. Tests can construct an environment with any tile side.

Two existing scenarios become invariance tests, parameterized over the tile side and the thread
count, so that no combination of layout and parallelism can hide a dependency the others do not
show:

- the label-selection scenario of `DeterministicExecutionTest`, which exercises the candidate order
  of the label index;
- the birth scenario of `ResumeForkNeutralityTest`: a 64×64 world, one birth with mutation rate
  1.0, all four mutation plugins, the whole state compared tick by tick through the existing
  `ResumeNeutralityHarness`. It exercises the mutation operators' cell order and the seeding of the
  newborn, and the harness guarantees that the second scenario compares no less than the first.

Each scenario runs with `t = 1` and `t = 32`, with one and two threads, and must end in identical
environments and organism states. No new replicating program enters the suite.

Oracle: the invariance test, the full suite, and the reference hash equal to main. This is the slice
that exposes hidden couplings: any code that still assumes row-major numbering fails the invariance
test, not a production run.

### Slice 4 — tiles in production

The production tile side becomes 32; shape validation and message; the `reference.conf` comment;
the hot loops of `Organism` use `step`.

Oracle: reference hash equal to main at `t = 32` — the same simulation, only faster. Then the
measurement: the real-world comparison on the demo server against main (now directly comparable in
wall-clock, no normalization), a local x86 profile for the share of fetch and skip, and the
detailed-profile comparison for the encoder. Acceptance: at least the 15 % of the experiment per
organism-tick, and no measurable encoder cost.

### Slice 5 — closure

`AGENTS.md` receives the SPI rule that environment indices are opaque. This document moves to
`docs/outdated/proposals/accomplished/`. The experiment branch and its worktree are deleted.

All slices land in one pull request, because only together they satisfy the invariance oracle;
each slice is a separate commit with its oracle stated in the message.

## Architecture ledger

What becomes better:

- Layout knowledge in one class instead of eight places; the toroidal wrap arithmetic, currently
  written three times in `Organism` and `EnvironmentProperties`, exists once.
- Two worldgen plugins lose their private stride computation; one plugin and the label strategy lose
  their dependency on `EnvironmentProperties` for index decoding.
- A new, tested invariant: simulation semantics never depend on memory layout. It is also the
  invariant a future spatial decomposition of the engine relies on.
- `DeathContext` gains the same content-defined iteration order that the mutation operators
  already needed for resume neutrality.

What is added:

- One class in the runtime model package and five methods on `Environment`.
- One rule for world shapes, checked at startup.

What is not changed:

- Persisted formats, resume, pipeline, CLI, visualizer, configuration keys.
- The coordinate-based `Environment` API and every caller of it.

## Out of scope

- Non-cubic tiles and a variable tile side. Both are compatible with this design and are not needed
  for 2D and 3D worlds.
- Spatial decomposition of the engine across threads or machines. Tiles become its natural unit of
  ownership, but nothing here anticipates it beyond the invariant above.
- The coordinate-based environment API and its allocations, a separate performance item.

## Outcome

Implemented in the slices above, with six additions decided during implementation:

- **The layout index is confined to the model package.** `GridLayout` and every index-based method
  of `Environment` are package-private; outside `org.evochora.runtime.model` no method takes or
  returns a layout index. Callers see coordinates, a `CellView` for owned cells and
  flat-index visits for serialization. The invariance is thereby enforced by the compiler,
  not only by tests.
- **Persisted cells are written in ascending flat-index order**, so persisted bytes are identical
  whatever the layout. The ordering is done by the environment: an in-place MSD radix sort over
  keys that carry each cell's content, read during a sequential walk of the grid, 12 bytes per
  occupied cell, retained between captures. The memory estimate separates the world size from the
  occupied cells and names the sort batch, the third bit set and the encoder's column lists.
- **The death-handler SPI lost `DeathContext.getFlatIndex()`**, which returned the environment's
  layout index. A handler sees the molecule of
  the cell it is visiting and may replace it; where the cell lies is no longer observable. No
  handler in the repository used the index; an external one that did must switch to the molecule
  accessors.
- **Conflict resolution stays keyed by the flat index.** `Simulation.resolveConflicts` groups
  contenders by the flat index of the target coordinate; a layout-index key would need an
  index-returning method outside the model package, which the first addition forbids.
- **The layout has no distance primitive.** The label index is keyed by the flat index, and the
  toroidal Manhattan distance to a label is computed from that index with the strides of
  `EnvironmentProperties`, without any knowledge of tiles; `distance(coord, index)` from the
  primitives table was therefore never built.
- **Coordinates outside the world are rejected.** The environment's in-range accessors, including
  the instruction fetch, throw an `IllegalArgumentException` for a coordinate outside the world
  instead of addressing another cell; in a bounded world a pointer that crosses an edge therefore
  stops the engine. Measured at about 1.4 % of the sparse profile, nothing in the detailed
  profile.

Measured on the demo server (ARM, 4 cores), same simulation, tick hash identical to main:

| Profile | main | tiled |
|---|---|---|
| sparse, 10 M ticks, ~100 organisms | 415–431 s | 356–368 s (−14 %) |
| detailed, 100 k ticks, 1 organism, 2000 snapshots of 83 000 cells | 37–38 s | 42–43 s (+14 %) |
| peak live set, sparse / detailed | 372 / 327 MB | 375 / 329 MB |

The detailed-profile cost is the serialization of a snapshot from an order that is no longer the
memory order: sorting, conversion and message assembly. The gap search of the insertion plugin
turned out to be coordinate-ordered already, so four rather than five decisions had to be redefined
over the flat index.
