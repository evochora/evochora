# Probabilistic Conditionals: Decisions as Dials for Evolvable Programs

**Status: DECIDED — reviewed against the architecture guidelines in two review rounds; to be
implemented on branch `probabilistic-conditionals`. Decisions taken with the maintainer are listed
at the end.**

## Problem

A program that decides by comparison offers evolution a switch. `GTI %ER D100000` followed by
`JMPI MAIN_REPRODUCE` is either taken or not; there is no setting in between, and the only way a
mutation can change the decision is to break the comparison. The conditional-skip semantics make
the break easy: a failed comparison skips the next real instruction, walking over NOPs, so
anything inserted into the padding between the comparison and its jump is skipped instead of the
jump, which then executes unconditionally. A switch can only be flipped, and a flip has no
intermediate that selection could tune.

This is what every selective sweep observed on this platform looks like. The primordial decides
between reproduction and harvesting in `MAIN_LOOP` with two such comparisons, and four independent
runs fixed a mutation at exactly this locus, every one of them by breaking a comparison, never by
moving it:

| Run | Fixed change | Effect | Rise |
|---|---|---|---|
| `20260226-03114337` (published) | substitution destroys the energy `GTI` | energy route closed | s ≈ 0.11 per generation |
| `20260402-11564129` (published) | `NOT` inserted in the padding behind the entropy `GTI` | entropy route always open | fixed in ≈ 90 generations |
| `20260902-15025884` | two-cell loss of `GTI %ER` (inherited body damage) | energy route always open | fixed in ≈ 35 generations |
| `20260907-23251690` | `PCNS` inserted in the padding behind the entropy `GTI` | entropy route always open | s ≈ 0.48 per generation |

Three facts from the life tables of the last two runs say why a switch is all the population can
use:

- Under the current mutation operators, most mutations have no phenotype. Duplication, insertion
  and label substitution reproduce at the clone baseline (run `20260907`: clones 12.2 %,
  duplication 10.8 %, insertion 10.8 %, label insertion 11.5 %). The mutation classes that change
  fitness are the destructive ones: substitution of a register (0 of 20 fertile) and, among opcode
  substitutions, the flips that leave the instruction's family or variant (5–6 % fertile in run
  `20260908`, against 15.5 % for a flip within the family).
- The thresholds are DATA operands and are mutated, but the steps are too small. Four
  substitutions in run `20260907` touched the energy threshold, by at most 1.5 %. To carry 100 000
  below a child's 25 000 in steps of that size takes ~60 steps, none of which changes behaviour
  until the last. A plateau of that length always loses to a one-step break.
- The break has no intermediate. In run `20260907` the unconditional entropy route multiplied the
  population by twenty in 15 M ticks, ate the free energy of the world from 71 000 cells to 20 000,
  and left a crowded regime with a generation time of 4 M ticks. A population that could have
  moved the decision by a fraction would not have had to jump.

The requirement is therefore a property of the instruction set, not of one program: where a
program decides by comparison, its author needs a way to write the decision as a probability that a
parameter moves in small, selectable steps, in both directions, without a plateau and without a
cliff — and the same construct must be reachable by evolution from a hard comparison in one step,
so that programs written with hard comparisons can soften on their own.

## Solution

Five parts, one pull request, each part its own commit or commits.

1. **Probabilistic conditionals in the ISA.** Twelve opcodes that compare against a random value
   instead of a fixed one, with the operand signatures of the existing comparisons, so that a
   single operation flip of the substitution plugin turns a hard comparison into a soft one. The
   comparison family also gets a defined meaning for vector operands, which today are compared
   for equality whatever the operation says.
2. **The primordial as the first application.** Its two decision points become soft gates whose
   scales start at the policy the sweeps converged on, so that no run has to rediscover the known
   sweep before anything else can be selected. A third, redundant check is removed because under
   the new policy it has no purpose and would be the last hard switch on the path.
3. **A genome frame.** A runtime building block that reconstructs, as the VM reads it when it
   enters at a label, which cell is an instruction, which operand slot and which vector component.
   The scan-line rule the duplication plugin uses to bound an organism's extent on a line is
   extracted into a class both can share.
4. **Substitution by operand slot.** The substitution plugin weights cells in a scalar immediate
   slot and in a vector slot with configurable multipliers on top of the type weights, records the
   slot in the mutation event, and gets a family flip that is never empty.
5. **Configuration.** A substitution rate at which threshold tuning becomes measurable, the STATE
   write rule set so that only replication dissipates entropy, and a template that shows the
   defaults.

## Part 1: Probabilistic conditionals

### Semantics

The second operand B is replaced by a uniformly distributed random integer U from [0, B), and the
comparison is evaluated between A and U exactly as its hard counterpart evaluates A against B:

| Hard | Meaning | Soft | Meaning with U ~ [0, B) | Probability the condition holds |
|---|---|---|---|---|
| GT | A > B | PGT | A > U | clamp(A / B, 0, 1) |
| LET | A ≤ B | PLE | A ≤ U | 1 − clamp(A / B, 0, 1) |
| LT | A < B | PLT | A < U | 1 − clamp((A + 1) / B, 0, 1) |
| GET | A ≥ B | PGE | A ≥ U | clamp((A + 1) / B, 0, 1) |

PGT/PLE and PLT/PGE are exact negation pairs, registered with `regPair` so that the compiler's
procedure marshalling, which needs the negation of a conditional that precedes a `CALL`, works for
them as for every other pair. A negative A never satisfies PGT or PGE because U is never negative.
The equality family gets no twin: "A equals a random number below B" is well defined but has no
use, and every extra opcode dilutes the operation flip.

`PGTI %ER D50000` is the soft form of `GTI %ER D50000`: certain from 50 000 energy on, 50 % at
25 000, 10 % at 5 000. The scale B is the point of certainty and, below it, the slope.

**B ≤ 0 yields U = 0.** `nextInt(1)` already returns 0 without exception; defining B ≤ 0 as U = 0
continues that behaviour downward instead of failing: PGT degenerates to `A > 0`, PLE to `A ≤ 0`,
PLT to `A < 0`, PGE to `A ≥ 0`. A scale mutated to nothing then leaves a hard test against zero,
not a permanently failing instruction with a 100-energy penalty on every pass. This differs from
`RAND`, which fails for a bound ≤ 0; the difference is deliberate and stated in the specification.

### Vector operands

The value comparisons today compare two vector operands for equality whatever the operation says
(`GTR` and `LETR` on two vectors are both an equality test, so they are not each other's
negation), and fail with "Mismatched operand types" when one operand is a vector and the other a
scalar. Both are replaced by one rule for the whole comparison family, hard and soft:

- two scalars: as today;
- two vectors: equality (IF/IN) componentwise, as today; order (GT, LET, LT, GET and their soft
  forms) by the Manhattan magnitudes, the sum of the components' absolute values;
- a vector and a scalar: the vector's magnitude, which counts as a `DATA` value, against the
  scalar, for equality and order, under the value-compatibility rule that applies to two scalars:
  against `DATA` or `STATE` the comparison is decided by the numbers, against any other type it is
  not satisfied, as `DATA` against that type is not;
- a location register that holds no position (`LocationValue.NONE`) has magnitude 0: against
  another location value it is equal only to one that holds no position either, against a scalar
  it enters with 0; a program that wants to ask whether a register holds a position has `IFSL`.

The Manhattan magnitude is an integer, costs one addition per dimension, and is the metric the
platform uses elsewhere (label matching measures toroidal Manhattan distance). No type-mismatch
failure remains in the comparison family; the type comparisons `IFT`/`INT` compare molecule types
and are unaffected. The soft forms draw U from [0, |B|), where the magnitude of a scalar is the
scalar itself. Two vector registers are ordinary in the primordial (`%DIRVEC`, `%SIDEVEC`), so an
operation flip that lands a comparison on them is a live case, and a soft comparison must not
silently degrade into a deterministic equality test there.

### Names, opcodes, variants

Mnemonics are four letters, the last one the variant, so the operation is three: PGT, PLE, PLT,
PGE. Variants RR, RI and SS as for the hard comparisons: PGTR/PGTI/PGTS, PLER/PLEI/PLES,
PLTR/PLTI/PLTS, PGER/PGEI/PGES. Operations 20 to 23 of the conditional family (operations 0 to 19
are taken, 18 and 19 by `IFSL`/`INSL`; the field has 64). No existing name or number changes. The mnemonic list of the VS Code
extension (`extensions/vscode/src/extension/syntaxes/evochora.tmLanguage.json`) mirrors the
registry by hand and gains the twelve names.

### Runtime, determinism, cost

One draw from `OrganismRandom.nextInt(B)` per execution, the same source `RBIR` and `RAND` use;
it is computed from seed, tick, organism and draw position, so it is allowed in the parallel
execution wave and needs no persisted state. A draw advances the organism's draw index for the
tick, so every later draw of that organism in the same tick (label matching, `RAND`, `RBIR`)
shifts; the trajectory stays a pure function of seed and configuration, but no run recorded before
this change replays identically under the new build, which is the rule for every run on this
platform anyway. The four new operations dispatch as further cases of the existing `switch` over
the instruction name inside the value comparison, so the hard conditionals pay nothing; a soft
conditional pays the draw, a few nanoseconds against the instruction's own cost. Thermodynamic
cost is the default of the instruction family (1 energy, 1 entropy).

### Why evolution reaches them by itself

`GeneSubstitutionPlugin` flips an opcode to another one of the same family and variant
(operation flip). The RI variant of the conditional family has eight operations today, twelve
afterwards; a hard `GTI` therefore becomes `PGTI` in one substitution with probability 1/11 among
the alternatives, and every other operation flip of the hard comparisons is diluted from 1/7 to
1/11. Every hard gate in every genome is one step away from being soft.

### For authors of primordials

The specification gets, next to the instruction definitions, a section on how to use them: a
hard threshold T becomes a soft gate with B = T, certain at T and graded below; a dead zone
("never below MIN") is composed the way the ISA composes every AND, with the negated hard test
and a jump away before the soft gate; a scale mutated downward drifts toward "always" without a
cliff, a scale mutated upward makes the decision more demanding; and a hard comparison can become
soft by a single substitution, so an author may also write hard comparisons deliberately and leave
the softening to evolution.

## Part 2: The primordial's decision points

`assembly/primordial/main.evo` uses the new instructions at the two places where the main loop
decides, with the existing constants and no new ones:

```
.DEFINE REPRODUCTION_CONTINUE_THRESHOLD DATA:50000          # was 100000
.DEFINE ENTROPY_REPRODUCTION_CONTINUE_THRESHOLD DATA:50000  # was 5000; half of the new maximum entropy
```

| Site | Today | Afterwards |
|---|---|---|
| energy route in `MAIN_LOOP` | `GTI %ER REPRODUCTION_CONTINUE_THRESHOLD` | `PGTI %ER REPRODUCTION_CONTINUE_THRESHOLD` |
| entropy route in `MAIN_LOOP` | `GTI %SR ENTROPY_REPRODUCTION_CONTINUE_THRESHOLD` | `PGTI %SR ENTROPY_REPRODUCTION_CONTINUE_THRESHOLD` |
| `MAIN_LOOP_ENERGY_CHECK` after a reproduction | two `GTI %ER … JMPI MAIN_LOOP`, then `JMPI MAIN_HARVEST` | removed; `MAIN_REPRODUCE` jumps to `MAIN_LOOP` after the call returns |

The energy scale starts at 50 000, the copier's pause threshold (`REPRODUCTION_PAUSE_THRESHOLD`,
unchanged, still hard). That is the *eager* policy every sweep produced: reproduce as soon as the
copier would not pause, harvest otherwise. Below 50 000 the gate opens with p = ER / 50 000; those
attempts bounce off the pause threshold inside `REPRODUCE.CONTINUE`. Raising the scale by mutation
makes the policy buffering (start copying later, pause less); lowering it changes nothing that
selection could see, because from 50 000 on the gate is already certain and below it an attempt
only bounces. The entropy scale is half of the organism's maximum entropy, as it was before (5 000 of 10 000):
with the maximum at 99 999 it becomes 50 000, certain there, half at 25 000. The translation rule
is the same at both sites: a hard threshold T becomes a soft gate with B = T; a scale at the
maximum itself would only become certain at death.

`MAIN_LOOP_ENERGY_CHECK` is removed rather than softened. It decided after a reproduction whether
to continue or to harvest, which is what `MAIN_LOOP` now decides with the same constants; and its
two consecutive checks were a redundancy against mutation that, kept as hard comparisons, would be
the last switches on the decision path, while two soft checks in a row would compound to
1 − (1 − p)² and give the site a different curve from `MAIN_LOOP` for no reason. After the call
returns, the organism goes to `MAIN_LOOP`. The one behavioural difference is that the entropy route
gets its chance after a reproduction too, which below 50 000 energy can cost one more bounce. The
"redundant safety jumps" at the row's end stay; they are not a gate but catch an instruction
pointer that runs off the row.

Under the eager start the known break has no purchase any more: an insertion that makes a soft
gate unconditional changes nothing from 50 000 on and, below it, turns dice into certain attempts
that bounce, each costing about forty instructions. That is a small cost without benefit; the break
was only better than a gate *closed* at 100 000.

**Also changed, as its own small commit:** `REPRODUCE.CONTINUE` checks the pause threshold first
thing, before it loads its state, sets the marker or saves a pointer, and returns with a plain
`RET` when the energy is below it, because nothing has been touched yet. A bounced attempt then
costs about ten instructions instead of forty, and the first attempt after a completed child no
longer walks to the corner (about 1 500 instructions) only to bounce there. The check inside the
copy loop stays; it pauses a copy whose energy runs out between rows.

**Not changed:** the copier's pause (`LTI ER REPRODUCTION_PAUSE_THRESHOLD`, 50 000, hard). It
protects against dying in the middle of a copy and is the floor the energy gate stands on. **Not
added:** an abort in `HARVEST` at high entropy. With the eager policy an organism that could
reproduce never enters a harvest walk, so the case that abort was for (23.7 % of the entropy deaths
of run `20260902` had ≥ 50 000 energy and died walking) disappears by itself. It is recorded as a
later item in case buffering policies appear.

The operand cells of the two scales sit at `[15,4]` and `[28,4]` relative to the organism's origin
(`PGTI` occupies the same three cells as `GTI`), which is where expectation 3 reads them.

## Part 3: The genome frame

### What it is

A runtime building block, `org.evochora.runtime.model.GenomeFrame`, next to `GenomeHasher`, which
also walks a newborn's owned cells from its origin. It assigns every cell of an organism's genome
the role the VM gives it when it enters the code at a label or at the organism's start position:
the instruction the cell belongs to, the operand slot inside that instruction, and for vector
operands the component index. It exists because the code stream is not self-synchronising. An
operand cell can carry any molecule type, `CODE:0` included (`SETI %TMP CODE:0` in
`CONTINUE_READLINE`), so no local inspection of a cell or of its neighbours can tell an opcode from
an immediate or a gap from an operand. Only a forward parse from a point where the instruction
pointer can be knows the reading frame.

### How it is built

For each frame start — every `LABEL` cell the organism owns and the organism's initial position —
the frame walks along the organism's direction vector as the VM would:

- a non-`CODE` molecule or `CODE:0` at an instruction start is a NOP, one cell;
- a registered opcode consumes the cells its signature declares (`REGISTER`, `IMMEDIATE`, `LABEL`
  one each, `VECTOR` as many as the world has dimensions, `STACK` none), whatever molecules those
  cells hold, and assigns each of them slot and component;
- an unregistered opcode is one cell, as `getInstructionLengthById` reports it to the VM;
- the walk does not stop at cells owned by others, because the VM does not stop there either;
- the walk ends at the end of the organism's extent on that line.

**The extent on a line** is the rule the duplication plugin already applies to bound its scan
lines: the arc from the smallest to the largest coordinate of the organism's cells along the
direction vector, which in a toroidal world may cross the edge; if that span exceeds half the
world, the largest gap between owned cells is taken as the outside. Today that rule is a private
method on the duplication plugin's pooled state and wraps unconditionally, also in a bounded
world, where the VM does not wrap. It is extracted into `org.evochora.runtime.model.ScanLineArc`,
a pure function from the sorted coordinates of a line to its start and end, with the world's
toroidality as a parameter; the duplication plugin calls it in place of its private code, and in a
bounded world it no longer wraps — a latent inconsistency corrected in its own commit. Gaps inside
the arc (non-contiguous ownership, foreign cells) are walked as the VM walks them. The frame has no
skip budget; it executes nothing.

A cell no walk reaches is *outside any instruction*; so are labels and the cells of a NOP. A cell
two walks reach with different roles (a label placed inside another instruction's operand list) is
*ambiguous* and is treated as outside any instruction. Entry points that only exist at runtime — a
jump by a vector from a register or the stack, the re-entry after a stall — are not frame starts:
they are behaviour, not structure. For a program that uses them, the frame describes the layout the
compiler emitted, and the weighting and the labelling of Part 4 follow the layout rather than that
program's reading; no mutation becomes impossible or of the wrong kind, only its selection
probability and its label follow the layout. The primordial enters code only through `JMPI`,
`CALL` and `SKJI` on labels, so nothing changes for it.

The frame is a pure function of the grid, the start cells and the direction vector, so it is
deterministic. It is built inside `substitute()` of the substitution plugin, after the rate gate,
so it is computed only in the births in which a substitution actually happens (one in fifty at the
new rate), and its buffers are pooled in reusable fields the way the duplication plugin pools its
scan-line objects, so that after warm-up it allocates nothing. It is recomputed on every call and
never cached, because the plugins before it in the birth sequence change the genome. Cost, an
estimate from the shape of the work: one linear pass over the owned cells with one registry lookup
per opcode, microseconds, in the birth path, behind the rate gate — not on the tick path, and not
measured with the tick benchmark, which measures execution.

The frame reads along the organism's direction vector at birth, as the four mutation operators
already do. `TURN`, `.DIR` and the direction operand of `FORK` stay in the language as they are.

## Part 4: Substitution by operand slot

### Weights by slot

The substitution plugin builds the frame for the newborn and multiplies the type weight of every
cell with a factor for the slot it stands in:

```
operands {              # multipliers on the type weight, by the operand slot a cell stands in
  scalar = 2.0          # a cell in a scalar immediate slot
  vector = 1.0          # a cell in a vector operand slot
}
```

The type says what the molecule is; `operands` says where it stands. A DATA literal weighs
`DATA.weight × operands.scalar`, an ENERGY literal `ENERGY.weight × operands.scalar`, a vector
component `DATA.weight × operands.vector`, and every cell in neither kind of slot — opcodes,
register and label slots, labels, cells outside any instruction, ambiguous cells — weighs its
type weight. Both keys are mandatory, no other key is allowed, and the type blocks are unchanged.
The perturbation itself is unchanged (`delta = max(1, round(|v|^exponent))` with the type's
exponent, a uniform offset in [−delta, +delta]).

**Configuration validation goes down to the blocks.** Today unknown keys are rejected at the top
level only; a misspelt key inside a type block is silently ignored. Every type block and the
`operands` block get a fixed set of allowed keys, and any other key is rejected at load time with
the block and the key named.

### The slot in the mutation event

Every substitution record carries one parameter, the slot code: 0 for neither kind of slot, 1 for
a scalar immediate slot, 2 for a vector slot. The kind keeps a fixed arity like every other kind
(duplication, deletion, label insertion and label rewrite carry one parameter each, insertion
none), which is what a reader of `mutation_summary` — where the parameters travel as a JSON list —
relies on. The plugin's Javadoc, which says "no parameters" today, documents the code; the test
that asserts an empty parameter list for a CODE substitution asserts the code 0 instead.

### The family flip

The plugin flips an opcode in three ways: operation (same family, same variant, another
operation), family and variant. The family flip today requires the *same operation number* in
another family. Operation numbers carry no meaning across families (operation 3 is `GT` in the
conditional family and something unrelated elsewhere), and the requirement makes the flip empty
for every opcode whose operation number no other family uses — today `INER` (operation 17) and `IFSL`/`INSL` (18 and 19), after
this change all twelve soft comparisons (20 to 23). An empty flip returns the old value and
`substitute` returns without a record: a fifth of the substitutions landing on a soft gate would
do nothing and leave no trace. The family flip is therefore redefined as **same variant, another
family, any operation**: what it preserves is the signature, and that is stated. The variant flip
(same family and operation, another variant within the arity group) and the weights
0.7 / 0.2 / 0.1 are unchanged. Measured on run `20260908-12095286` (29 M ticks, 41 533 births,
clone baseline 14.1 %): operation flips reproduce at 15.5 % (n = 110), family flips at 5.3 %
(n = 19), variant flips at 6.3 % (n = 16). Rewriting the operands on a variant flip so that they
fit the new signature is issue #167.

### What it changes in numbers

Per birth today, a substitution happens with probability 0.01; DATA carries a third of the weight,
literals a third of DATA's cells, and the two gate scales are two of some 55 literals: about one
scale hit per 14 000 births. At rate 0.02 with `operands.scalar = 2.0` it is one per ~7 000
births, about ten per run of 75 000 births — enough to see whether the scales move on the trunk
and in which direction, not for a distribution. `operands.scalar` is the dial if expectation 3 is
to weigh more: every doubling doubles the scale hits at the expense of the vector share.

## Part 5: Configuration

`reference.conf` carries the defaults, `config/evochora.conf` is the template users change and,
in the repository, shows the defaults; `config/local.conf` is the maintainer's experiment file, is
not tracked, and is adjusted on the maintainer's machine outside the pull request, existing
overrides only.

| Key | `reference.conf` today | run `20260907` | new default | why |
|---|---|---|---|---|
| `substitutionRate` | 0.025 | 0.01 | 0.02 | scale hits measurable; total plugin load rises from 16 % to 17 % of births |
| `CODE`, `REGISTER`, `DATA`, `LABEL`, `LABELREF` weights | 1.0 / 1.0 / 1.0 / 1.0 / 1.0 | 1.0 / 0.2 / 2.0 / 1.0 / 1.0 | as in the run | the maintainer's calibration, unchanged |
| DATA exponent | 0.7 | 0.9 | 0.9 | at 50 000 a step of up to ±34 %, at 5 000 up to ±42 % |
| `operands` | — | — | scalar 2.0, vector 1.0 | Part 4 |
| `ENERGY`, `STRUCTURE`, `STATE` blocks | weight 0 | absent from `evochora.conf` | present with weight 0 | the template shows the defaults; a block inside a list element replaces rather than merges |
| write-rules `STATE.entropy` | −500 | −500 | 0 | below |
| `max-entropy` | 10 000 | 10 000 | 99 999 | below: the clock of the refill phase; five digits keep it readable in the visualizer |
| seed energy | 0.0025 × 10 000 | 0.002 × 10 000 | 0.01 × 5 000 | packets of 5 000: the regime of the fourth smoke run, below |
| solar radiation | 0.02 × 10 000, radius 1 | 0.005 × 10 000, radius 0 | 0.05 × 5 000, radius 0 | five times the input of the run in half-size packets |
| geyser | 0.0002 × 10 000, radius 3 | 0.0001 × 10 000, radius 0 | 0.0001 × 5 000, radius 0 | half-size packets |
| `deletionRate`, `duplicationRate` | 0.025, 0.1 | 0.01, 0.1 | as in the run | unchanged |

Rates per birth under the new default, against run `20260907`: opcode flips 0.0073 (was 0.0041),
DATA literals 0.0040 (0.0011), DATA vector components 0.0040 (0.0022), label and label-reference
flips 0.0039 (0.0022), register flips 0.0009 (0.0005). No class more than doubles; the type weights
keep the maintainer's calibration.

A geyser places one packet every `interval` ticks into an empty neighbouring cell and stops when
its neighbours are full, so its input is limited by demand, not by its count: energy in this world
is spatially concentrated rather than globally scarce, and smaller solar and seed packets change
the walk variance, not the carrying capacity.

**STATE writes stop dissipating entropy.** Entropy exists so that an organism that stops
replicating dies (`docs/SCIENTIFIC_OVERVIEW.md`, section 2.5). Today every STATE write dissipates
500 like every other write: 1 000 at birth (`LOCALSTATE_WRITE`), 1 500 per energy find
(`HARVEST_SAVE_AND_RETURN`), 1 000 per bounce off the pause threshold (`CONTINUE_STORE_STATE`).
With the rule at 0 only writing genome — copying — dissipates entropy. Whether a written DATA
molecule is stored as DATA or as STATE is decided by the marker register at the moment of the
write (`Molecule.storedFormOfWrite`): the copier sets the marker before copying and clears it before
the state store. A copier that lost its `SMRI DATA:1` would write its child as STATE, produce no
viable child, and today live on the entropy those writes dissipate; with the rule at 0 it dies on
the clock. The rule closes that case rather than opening it.

**What the smoke runs showed.** The maintainer ran four short runs on the 7680 × 4320 world
(seed 42, substitution rate 0.02) while the primordial was being changed:

| Run | Configuration | At 6 M ticks: alive / born | Deaths by energy / entropy | Children that reproduce |
|---|---|---|---|---|
| 1 | soft gates; bounced attempts still store state (−1 000 entropy each); max-entropy 10 000 | 298 / 792 | 387 / 1 192 | 30.6 % |
| 2 | as 1, but the copier refuses before touching anything | extinct after 1.1 M ticks, 5 born | 0 / 4 | — |
| 3 | as 2, max-entropy 100 000 | 62 / 418 | 1 151 / 5 | 30.7 % |
| 4 | as 3 with max-entropy 99 999, STATE write entropy 0, entropy scale 50 000, energy in packets of 5 000 | 290 / 2 039 | 1 704 / 155 | 40.3 %, generation time 401 000 ticks |

Run 1 was the largest population the platform had produced, and it lived on a loophole: an
attempt bounced below the pause threshold wrote two STATE cells and dissipated 1 000 entropy, so
the soft gate, which sends organisms below 50 000 into that bounce again and again, was an entropy
sink during harvesting. Run 2 removed the sink and showed what the STATE rule would have done at
a budget of 10 000: every adult died of entropy at 41 000–44 000 energy, refilling toward the
threshold. The entropy clock had never been "replication only"; it was carried by the state writes.
Run 3 raised the budget and energy became the only binding quantity; run 4, with the rule at 0
and half-size packets, reproduces twice as many children per tick as run 1 at the same
population, with the shortest generation time measured so far, and its deaths are starvation, not
the clock. Run 4 is the regime of this package: energy binds, the clock is a bounded backstop
against copiers that never replicate (they die after 99 999 instructions rather than 10 000),
and children die by the availability of energy near them, in about 30 000 ticks when none is.

`docs/SCIENTIFIC_OVERVIEW.md`, section 2.3, says that writing molecules via POKE reduces the
organism's entropy register; with this rule that is true of every type but STATE, and the sentence
is adjusted with the maintainer's approval of the hunk.

## Implementation steps and verification

One commit per step, in this order; every step builds and tests on its own, so a failure is found
in the step that caused it. `pgrep -af "^java.*jmh"` before every Gradle invocation.

| # | Step | Files | Verification | Done when |
|---|---|---|---|---|
| 1 | ISA opcodes and vector semantics | `ConditionalInstruction.java`, `docs/ASSEMBLY_SPEC.md`, `evochora.tmLanguage.json` | new tests in `VMConditionalInstructionTest`: hit rate of `PGTI` with A = 50 000, B = 100 000 over 10 000 draws at a fixed seed within 0.50 ± 0.02, 0 at A = 0, 1 at A ≥ B; `PGTI`/`PLEI` at B = 1 are exact complements on every A; B ≤ 0 gives the hard tests against 0 for all four; two vectors compare by magnitude for the eight order operations, hard and soft, and `GTR`/`LETR` are complements; a vector against a scalar compares magnitude against scalar for equality and order; `ConditionalNegationTest`: the four pairs both ways; `ConditionalInstructionCompilerTest`: `PGTI %DR0 DATA:5` compiles to three cells; `GeneSubstitutionPluginTest`: the operation-flip alternatives of `GTI` contain `PGTI`; the determinism suite with a test program that executes `PGTI` in a loop. `./gradlew test --tests '*Conditional*' --tests '*GeneSubstitution*' --tests '*Determinism*'` | twelve opcodes registered and specified with the authors' section, all listed tests green |
| 2 | Configuration, part 1 | `reference.conf`, `config/evochora.conf`, `docs/SCIENTIFIC_OVERVIEW.md` (one sentence, approved hunk) | STATE write rule, energy plugins, rates, weights, exponent, the three zero blocks (the `operands` block waits for step 6, because the plugin rejects unknown keys); the diff between `evochora.conf` and the defaults reviewed by eye; `config/local.conf` adjusted on the maintainer's machine. `./gradlew test --tests '*Config*'` | the two tracked files agree with each other in the reviewed diff |
| 3 | Primordial | `assembly/primordial/main.evo`, `lib/reproduce.evo` (pause check moved, own commit) | `AssemblyProgramsCompileTest` (compiles the primordial, so the new opcodes are exercised); the four smoke runs of Part 5, run by the maintainer on the branch, read for reproduction, death causes and generation time. There is no per-primordial simulation test in the suite; primordials are experiments | compiles; the regime of run 4 taken into the defaults |
| 4 | `ScanLineArc` | `runtime/model/ScanLineArc.java`, `GeneDuplicationPlugin.java`, `ScanLineArcTest` | hand-built cases (no random bodies): a line inside the world, a line whose arc crosses the toroidal edge, a span over half the world resolved by the largest gap, a bounded world without wrapping, a gap inside the arc; the duplication plugin's existing tests unchanged and green; the determinism suite. `./gradlew test --tests '*ScanLineArc*' --tests '*GeneDuplication*' --tests '*Determinism*'` | one definition of the extent, the duplication plugin on it, bounded-world correction as its own commit |
| 5 | `GenomeFrame` | `runtime/model/GenomeFrame.java`, `GenomeFrameTest` | hand-built grids: a straight instruction sequence gives slot and instruction per cell; a vector operand gives component indices; `SETI %T CODE:0` followed by a vector is parsed correctly (the `CODE:0` is an immediate, not a gap); a label inside an operand list marks its cells ambiguous; a cell no walk reaches is outside any instruction; a foreign cell inside the line is walked; an unregistered opcode is one cell; the line ends at the arc; the same grid gives the same frame twice. `./gradlew test --tests '*GenomeFrame*'` | class with Javadoc, all listed tests green, no user yet |
| 6 | Substitution by slot, configuration part 2 | `GeneSubstitutionPlugin.java`, the two tracked config files, `MutationSummaryPlugin` Javadoc | a configuration without `operands`, with a missing key, or with an unknown key in any block is rejected with a message naming block and key; with `vector = 0` and `scalar = 1` only cells in scalar slots change over many births of a test genome, and the reverse; every record carries the slot code; the family flip of `PGTI` is not empty and stays within the variant; the frame is built after the rate gate; the existing plugin tests updated for the parameter. `./gradlew test --tests '*GeneSubstitution*'` | slots weighted and recorded, family flip redefined, defaults in both tracked files |
| 7 | Whole | — | `./gradlew check` (PMD gate included); fetch and merge `origin/main` (the maintainer's unit-vector snapping arrives there), `check` again; push only with the maintainer's go, then CI and the review rounds | PR open, CI and reviews awaited |

## Pre-registered expectations for the next run

The run after this change is read against these statements, each with what would refute it. It
runs under the new packet density and the unit-vector snapping the maintainer is adding in parallel,
so its baseline is its own, not run `20260907`'s.

1. **No trivial sweep at the switch.** No trunk fixation makes a gate in row 4 unconditional
   (insertion in the padding behind a `PGTI`, substitution that destroys one), and no scale on the
   trunk falls below the operating range of its register (energy scale below the typical energy at
   the decision point, entropy scale below about 500), which would be the same break reached by
   parameter. Refuted by either; then the DATA step is still too small against the break and is
   the next lever.
2. **Insertion behind `PGTI` is near neutral.** It makes the jump unconditional again; from 50 000
   energy that changes nothing, below it turns dice into certain attempts that bounce. Expected:
   such insertions occur, reproduce at the clone baseline, do not fix.
3. **Policy selection appears as literal substitutions at the scales**, positions [15,4] and
   [28,4], read from `mutation_summary` as substitutions of type DATA with slot code 1 at those
   cells. Direction not predicted; in a scarce regime stable or rising, and no systematic downward
   drift under the eager start, because below the start value there is nothing to gain. About ten
   scale hits are expected per run; no such trunk substitution in 40 or more generations means
   either "start value at the optimum" or "steps too small", distinguished by the fertility of the
   scale mutants against the clone baseline.
4. **Operation flips to P-conditionals elsewhere** occur and reproduce below the clone baseline,
   because most hard conditionals of the primordial are guards (shell reached, cell foreign) that
   make no sense soft. A soft twin with high fertility somewhere else is a surprise worth a look.
   Vector components, which the snapping turns from a cliff into a neutral or a rotation, are
   read as their own class against the clone baseline.
5. **Entropy stays a backstop.** Deaths are by energy; entropy deaths stay a small minority
   (run 4: 155 of 1 859), and no organism with at least one child dies of entropy while refilling
   below 50 000. As the second observable, substitutions or deletions at the two `SMRI` cells of
   the copier and their fertility, which should be zero under the rule. A rise of entropy deaths
   into the range of the energy deaths refutes the budget, not the rule.
6. **Regime.** No boom from stored energy; the population grows straight into the carrying
   capacity. Generation time and clone fertility are recorded as the baseline for later packages,
   not as a criterion for this one.

## Out of scope, recorded

- **Probabilistic jump `PJMI`** (jump with probability P to a label, a new control-flow edge in one
  insertion): deferred to the insertion idiom. Fuzzy label matching already makes the *target* of
  a jump stochastic; whether a new edge with a small probability has value is open. The proposal
  `CONDITIONAL_BRANCH_ISA` counts twenty conditional operations; with this change there are
  twenty-four, and a branch variant of a soft comparison would be that probabilistic jump.
- **Harvest abort at high entropy**: see Part 2.
- **Operand rewriting on variant flips**: issue #167.
- **A VECTOR molecule type** (packed into one cell, one cell per component with an index, or with a
  header cell) was examined and dropped: with a fixed birth direction the frame yields the slot of
  every cell exactly and, when an operator needs it, the group; a type would add visibility at the
  cost of a cut through compiler, VM, registry, thermodynamics and both plugins, and the packed
  form would also limit component ranges and touch every instruction that moves vectors between
  cells and registers.
- **Direction-aware operators** (labels carrying a block direction, walks following `TRNI`) were
  examined and dropped: a runtime direction change is a property of the reader, not of a cell, and
  register-based or conditional turns stay outside any canonical structure. The operators act along
  the birth direction; the maintainer decided not to document this as a contract in this package.
- **Duplication of whole instructions** (43 % of copies in run `20260902` were fragments cut at the
  NOP boundary) and **insertion with deliberate targets** (80 % land in the small gaps between
  instructions of the execution path, 20 % in the padding behind a row's last jump, chosen
  uniformly) are follow-ups on the frame.
- **Generation time** (0.93 M ticks before the sweep of run `20260907`, 4 M after) is dominated by
  the harvest walk, not by copying (~30 000 ticks per child by instruction count); its lever is
  harvest logic and sensing range, a later package.
- **`org.evochora.runtime.model`** has become a catch-all and needs a clean-up; the frame and the
  arc rule go there because their nearest relative, `GenomeHasher`, is there, not because the
  package is right.

## Decisions taken with the maintainer

1. **ISA extension, not a macro in machine code.** A four-instruction idiom (`SETI`, `RAND`,
   compare, jump) can express a probabilistic gate today, but only where a programmer writes it;
   eight cells fit none of the 312 gaps between instructions on the primordial's execution path
   (against 42 padding runs behind a row's last jump and 42 empty rows), and the insertion plugin
   places one instruction. Only an instruction is one operation flip or one insertion away.
2. **The instruction set is the deliverable; the primordial is the first application.** The
   instructions are defined for any program an author may write, with a section for authors in the
   specification; nothing in their definition refers to this primordial.
3. **Lower bound fixed at 0.** A three-operand form (`MIN`, `MAX`) would express dead zones but is
   reachable by no flip; the dead zone is what the hard conditional already provides, composed the
   way the ISA composes every AND. A MIN/MAX form can be added later without breaking anything if
   the leak below MIN measurably matters.
4. **Both negation pairs (PGT/PLE, PLT/PGE), no equality twins.** The probabilistic difference
   between > and ≥ is 1/B, but the compiler needs an *exact* negation for a conditional before a
   `CALL`, and PLT is not the negation of PGT at A = U.
5. **B ≤ 0 → U = 0**, not failure as in `RAND`: the continuous limit of B = 1, so a mutated scale
   degrades to a hard test instead of a cliff. Kept after review: a scale drifting to nothing under
   the eager start is drift, not a sweep, and expectation 1 watches for it.
6. **Vector operands compare by Manhattan magnitude** for the order operations, a vector against
   a scalar by magnitude for equality and order, two vectors for equality componentwise; the hard
   comparisons change with the soft ones, and no type-mismatch failure remains in the family.
   Chosen over failing the instruction (a cliff where the hard comparison has none) and over
   mirroring the hard comparisons' silent equality test (which made PGT and PLE non-complements on
   vectors).
7. **Names PGT/PLE/PLT/PGE**, four letters with the variant last; PLET/PGET are impossible.
8. **Existing constants reused**, values changed: energy 50 000 (the point the sweeps reached),
   entropy 5 000 (the old threshold as the point of certainty, not 10 000, which is death).
9. **The primordial uses the instructions immediately** rather than waiting for the flip, so that
   no run has to rediscover the trivial sweep before anything else can be selected.
10. **`MAIN_LOOP_ENERGY_CHECK` removed, not softened**: no hard gate remains on the decision path,
    and two soft gates in a row would compound.
11. **The copier checks the pause threshold at its entry** and returns before touching anything,
    so a bounced attempt costs about ten instructions and no walk to the corner.
12. **No harvest abort this round**: made unnecessary by the eager start value.
13. **STATE write rule in this package, with `max-entropy` 99 999 and the entropy scale 50 000.**
    Four smoke runs by the maintainer decided it: the rule at a budget of 10 000 is extinction
    (run 2), the budget raised keeps both constraints with energy binding (runs 3 and 4).
14. **Energy in smaller packets, not less energy**: the maintainer's configuration; a smaller
    amount per cell rather than fewer cells if scarcity is to be tightened later, because the
    density sets the walk length.
15. **Substitution rate 0.02, type weights unchanged, `operands { scalar = 2.0, vector = 1.0 }`.**
    Chosen over a rebalanced spectrum (rate 0.03 with new type weights), which the maintainer
    judged a change of regime that nothing had asked for; one class doubles at most.
16. **Roles from a forward frame, not from a backward walk and not from a molecule type.** A
    backward walk to the nearest `CODE` cell is a reconstruction that `CODE:0` immediates defeat; a
    forward parse from the frame starts is the VM's own reading. Three variants of a VECTOR type
    were compared and dropped (see out of scope).
17. **`operands { scalar, vector }` as multipliers on the type weight**, both mandatory, no other
    keys, and nothing else in the configuration structure. Chosen over a DATA-only role block (the
    slot is a property of the position, not of the type; ENERGY and CODE literals exist) and over a
    list of roles that mirrored the molecule types (`instruction`, `register`, `label`), which the
    maintainer rejected as unreadable. Validation of unknown keys goes down to every block.
18. **`GenomeFrame` and `ScanLineArc` in `org.evochora.runtime.model`**, next to `GenomeHasher`;
    the frame is built inside `substitute()` behind the rate gate, pooled, recomputed per call,
    with no change to `IBirthHandler`. The arc rule is extracted, not duplicated; the duplication
    plugin stops wrapping in bounded worlds as a corrected latent inconsistency.
19. **Tests with hand-built cases, no random bodies; no per-primordial simulation test**
    (primordials are experiments and would each need one); the primordial is verified by the compile
    test and the smoke run. No test compares `evochora.conf` with the defaults: the file is the
    users' template, and only its state in the repository has to match.
20. **The slot code on every substitution record**, fixed arity 1, so that `params[1]` means the
    same thing on every record of the kind.
21. **Family flip redefined as same variant, another family, any operation**, never empty;
    weights unchanged; variant-flip operand rewriting is issue #167.
22. **`TURN`, `.DIR` and the `FORK` direction operand stay**, and the operators' birth-direction
    behaviour is not documented as a contract in this package; a claim that organisms depending on a
    second reading direction are selected away is not made.
23. **`config/local.conf` is adjusted locally and is not part of the pull request.**
24. **Rejected: "do nothing".** Every future run would begin with the same switch sweep and the
    same twenty-fold boom before selection could act on anything else.
25. **A vector's magnitude counts as a `DATA` value.** The mixed comparison of a vector against a
    scalar follows the value-compatibility rule of two scalars instead of bypassing it: against
    `DATA` or `STATE` the numbers decide, against any other type the comparison is not satisfied.
    Strict typing means that types carry meaning, and one pairing must not be exempt from it.
