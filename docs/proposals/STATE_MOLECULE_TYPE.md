# A STATE Molecule Type: Separating Soma from Genome

**Status: TO BE REVIEWED — decisions taken with the maintainer, listed at the end. Reviewed by a
second session and by the architecture reviewer; both sets of findings are incorporated.**

## Problem

An organism's body is both its genome and its working memory. The primordial keeps its
reproduction and harvest state in DATA cells of its own body (`HARVEST_STATE`, `CONTINUE_STATE`),
and rewrites them at runtime. Every DATA cell is therefore ambiguous: it is either a constant that
the code reads (an instruction operand such as `GTI %ER DATA:100000` or `SETI %KLEFT DATA:89`) or a
variable that the organism writes. The genome hash cannot tell the two apart and excludes all DATA
(`GenomeHasher`), because including the variables would make every copy of the same genome look
different: siblings inherit their parent's state slots as they were at copy time.

The cost of that exclusion was measured on run `20260902-15025884` (87 M ticks, 35 834 births,
~145 generations) and replicated on run `20260903-15200469`:

- The primordial body holds 162 DATA cells; 157 are instruction operands, 5 are state slots. All
  157 constants are invisible to the hash. Every mutation of a threshold or a harvest period counts
  as a clone.
- `GeneSubstitutionPlugin` weights DATA like every other type, so roughly one substitution in nine
  hits a DATA operand and is never recorded as a mutation.
- The three documented selective sweeps all hit the same reproduction switch in `MAIN_LOOP`, each by
  *destroying* the `GTI` comparison (substitution, an inserted `NOT`, and a two-cell loss), never by
  shifting its threshold. Whether threshold tuning happens at all cannot be seen with the current
  hash.
- The harvest movement parameters (`SETI %KLEFT DATA:89 / 97 / 101 / 103`, `GTI %KIDX DATA:3`) are
  the only graded knobs of the primordial's behaviour. Selection on them is exactly the kind of
  gradual, phenotypically visible variation the mutation regime is meant to produce, and it is the
  one class the data pipeline cannot observe.

The distinction has to be general. Evochora is an open platform: other primordials may keep their
behaviour in a DATA table, several primordials may share one world, and mutated code has no
compile-time metadata. Any rule that depends on how *this* primordial lays out its cells does not
qualify.

One constraint shapes every workable solution: the copy loop carries each cell of the body through
a register and the data stack into the child (`SCAN`, `PUSH`, `POP`, `PPKR`). Whatever marks a cell
as soma must therefore travel inside the molecule integer itself, otherwise it is lost on the way
to the child. That leaves the type bits.

## Decision

Runtime state gets its own molecule type, **STATE**, and the physics decides at write time which
DATA becomes STATE, using the Molecule Marker Register as the switch.

1. **New molecule type `STATE`** (`Config.TYPE_STATE = 0x07`), registered in
   `MoleculeTypeRegistry` and resolvable by name like the other types, so `.PLACE STATE:89 3|2`
   compiles without a compiler change. The molecule type, the `StateInstruction` family and the
   state macros in `lib/state.evo` are three unrelated uses of the word; the specification says so
   once.
2. **Write rule.** When an organism writes a DATA molecule to the environment (`POKE`, `POKI`,
   `POKS`, `PPKR`, `PPKI`, `PPKS`) while its Molecule Marker Register is 0, the molecule is stored
   with type STATE. With a non-zero marker register the type is kept. No other type is affected;
   `CODE:0` keeps its special handling. The rule lives in one function, *the stored form of a
   write* (raw molecule and marker register in, stored molecule out), in `Molecule`; the two
   instruction write paths and the thermodynamic policies call it, so the decision exists once.
   **Write costs follow the stored form**: the write rule of the thermodynamic policy is resolved
   after the conversion, symmetric to the read rules, which already key on the cell's actual
   content. The specification states this explicitly.
3. **Marker classes.** Marker 0 is by definition the *ephemeral* class: what an organism writes
   with marker 0 is its own memory, not part of any genome. Every other marker value is a *durable*
   class: cells written with it keep their type and are handed to a child by a `FORK` executed with
   that marker. An organism that wants to write heritable DATA at runtime writes it with a marker
   different from the one it forks with. To keep the definition without exception, **`FORK`,
   `FRKI` and `FRKS` fail when the marker register is 0** ("FORK requires a non-zero molecule
   marker register"); today such a fork hands the parent's entire body to the child. The check is
   the first thing the three handlers do, before energy is taken and before the child organism is
   created, because organism IDs feed the per-tick conflict priority and a create-then-fail order
   would shift every later ID.
4. **Value compatibility DATA ≡ STATE.** In scalar arithmetic, bitwise operations and value
   comparisons, STATE and DATA count as the same type under strict typing. The result type stays
   the type of the first operand, as today; every type-preserving instruction (arithmetic, bitwise,
   rotate, popcount, `RAND`, `RBIT`, …) therefore propagates STATE. The same compatibility applies
   where an instruction demands a DATA operand for a plain number: the shift amount of
   `SHL*`/`SHR*` and the operands of `SMR*`/`CMR*`. Type comparisons (`IFT*`, `INT*`) and type scans
   (`SNT*`) remain exact.
5. **Genome hash.** `GenomeHasher` excludes STATE instead of DATA. Everything else about the hash
   (types, label normalisation, sorting, the marker bits being part of the hashed value) is
   unchanged, and its signature does not change. The hash is a birth-time snapshot: at that moment
   every owned cell carries marker 0, so a cell written at runtime with a durable marker hashes
   differently in the writer than the same cell does in a child that later inherited it.
6. **Mutation.** Every registered type has a substitution strategy and a configurable weight. CODE,
   REGISTER, LABEL and LABELREF keep their specific strategies (opcode flip, ±1 within the bank, bit
   flips in the hash). Every other type that carries a numeric value — DATA, ENERGY, STRUCTURE,
   STATE, and any future type — uses the scale-proportional value perturbation that DATA has today,
   with its own configurable exponent. Defaults: DATA as today, ENERGY, STRUCTURE and STATE with
   weight 0. A missing type block in the configuration means weight 0. Nothing is excluded by code
   any more; the silent `else { return; }` in the dispatch disappears. `GeneInsertionPlugin` is not
   touched: it writes DATA operands directly into the environment without a marker register, and
   they stay DATA, which is what the genome needs.
7. **Thermodynamics.** `reference.conf` gets explicit STATE entries in the PEEK/POKE read rules
   (own, foreign, unowned) and write rules with the same costs as DATA.
8. **Primordial.** The three `.PLACE DATA` lines that seed state slots become `.PLACE STATE`
   (`lib/energy.evo` lines 16–18, `lib/reproduce.evo` lines 16–17). No literal, macro or
   instruction changes.
9. **One enumeration per side.** Adding a molecule type touches one place in the backend and one
   in the frontend besides the entries that are per type by nature (see *Consolidation*).

### Why the marker register

`FORK` transfers to the child every cell whose marker equals the parent's current marker register.
The handover mechanics therefore force only one thing on every viable replicator: the marker it
writes its own state with must differ from the marker it forks with, otherwise its state slots
leave with the child. Which marker is the somatic one is not forced; this proposal *defines* it as
0, and the `FORK` rule in point 3 makes the definition hold without exception. The primordial
already follows it: `SMRI DATA:1` at the start of `CONTINUE`, `SMRI DATA:0` in
`CONTINUE_STORE_STATE` *before* the state store (reproduce.evo line 446 before 448), harvest and
`INIT` run with marker 0, and `FRKS` executes while the marker is still 1. The marker register is
serialised with the organism, so a resumed run takes the same branches.

### Why the compatibility rule sits in the ALU

Immediate operands already adopt the type of the first operand in arithmetic and bitwise
instructions (`ArithmeticInstruction` lines 158–162 and 325–329, `BitwiseInstruction` lines
164–167); `SUBI %KLEFT DATA:1` on a STATE register keeps STATE today. Value comparisons do not
adopt it (`ConditionalInstruction` lines 266–270): a comparison between types is silently false,
which would break `IFI %KLEFT DATA:0`, `GTI %KIDX DATA:3`, `GTI %DIRMASK DATA:0` and the SIDEVEC
tests once the loaded value is STATE. Register-to-register operations need the rule as well:
`ANDR %MASK %FWD_MASK` (energy.evo line 109) combines a DATA register from `SNTI` with a STATE
register from the slot and fails today on type mismatch. Normalising the type on load instead
(`SCAN`/`PEEK` returning DATA) is not an option: the copy loop would then write the parent's slots
into the child as DATA, and siblings would differ again.

The rule is one static, allocation-free predicate on two type ints in `Molecule`, following the
precedent of `extractSignedValue`, with the equality fast path first. It replaces the type-equality
test at every site that has one; a grep over the instruction package finds exactly seven:
`ArithmeticInstruction` 167 and 333, `BitwiseInstruction` 171 (equality) and 177 (shift amount),
`ConditionalInstruction` 268, `StateInstruction` 735 (`SMR*`) and 814 (`CMR*`). Today the predicate
answers "equal, or DATA and STATE"; a later decision for value-based typing would widen the same
predicate.

### STATE literals in code

The primordial writes no STATE literal into an instruction, and the mutation plugins never generate
one. An author may: `SETI %X STATE:5` compiles, and the constant is then soma by declaration —
excluded from the hash, never substituted at the default weight, and interchangeable with DATA in
value operations. That is a legitimate tool (a constant protected from substitution, at the price
of being invisible to lineage tracking) and a legitimate scan target (`SNTI %M STATE:0` finds
memory cells in the neighbourhood). The compiler neither rejects nor warns.

### Lifecycle under the new rules

| Step | What happens | Type in cell / register |
|---|---|---|
| Birth (`FRKS`, MR 1) | cells with marker 1 move to the child, markers reset to 0; child MR = 0; hash computed after birth handlers | STATE slots excluded, DATA operands included; siblings identical |
| `INIT` → `LOCALSTATE_WRITE` | `SETI %INI DATA:0; PPKI %INI 1\|0` with MR 0 | register DATA, cell STATE, priced as a STATE write |
| Load (`SCNI`) | molecule read with its type | register STATE |
| Compare / compute | `GTI %DIRMASK DATA:0`, `SUBI %KLEFT DATA:1`, `ANDR %MASK %FWD_MASK` | compatibility makes them meaningful; results keep the first operand's type |
| Reset (`SETI %KLEFT DATA:89`) | constant from the code | register DATA; the constant stays a DATA operand in the genome |
| Store (`PPKI` in `STATIC*_STORE`, MR 0) | whatever the register holds | cell STATE |
| Copy (`SCAN`, `PUSH`, `POP`, `PPKR`, MR 1) | types preserved | STATE stays STATE, DATA stays DATA, STRUCTURE untouched |
| Write into a foreign body with MR 0 | e.g. a roaming data pointer during harvest | cell STATE, owned by the writer; the victim's copy loop removes foreign cells as today |
| Heritable runtime write | `SMRI DATA:2; PPKI …; SMRI DATA:0` | cell DATA with marker 2; stays with the writer at a marker-1 fork |
| Fork with MR 0 | mutant that lost `SMRI DATA:1` | instruction fails, nothing is transferred, the failure is counted like any other |
| Mutation plugins | write directly to the environment, no marker register involved | types as generated |
| Death | `DecayOnDeath` replaces with `CODE:0` | type-independent |

Clear-then-write (`PEEK` followed by `POKE`), scratch tables written next to the body, and leftovers
of aborted copies all follow the same rule: written with marker 0, they are STATE.

## Consolidation of type enumerations

Adding a type today means touching every place that enumerates the types one by one. In the
backend: the registry and its hand-written validator (`validateAllConfigTypesRegistered`, seven
if-blocks), the substitution plugin's weight table (`NUM_TYPES = 7`, cells of higher types silently
skipped), the composition plugin's column list, the minimap aggregator's index range (`NUM_TYPES =
8`, index 7 already means EMPTY, any higher type is counted as empty), two CLI colour tables (index
7 = EMPTY there too, and they disagree on the EMPTY colour), the death plugin's name switch and a
second name resolver in `Molecule`. In the frontend: the type constants and colours in
`AppController`, the name-to-index table, the colour switches *and* `getTypeName()` in
`EnvironmentGrid`, the index palette in `MinimapRenderer`, and `ValueFormatter`'s one-letter type
abbreviation (LABEL and LABELREF already both show as `L:`; STATE and STRUCTURE would both show as
`S:`). Documentation mirrors: the minimap byte encoding comment in `http_api_contracts.proto`, the
column list in `notebooks/data_analysis_guide.ipynb`, the type lists in the Javadoc of
`MinimapAggregator`, `EnvironmentCompositionPlugin`, `DecayOnDeath` (and its error message),
`IAnalyticsPlugin`, and the comment blocks in `reference.conf`.

Today an unknown type is also handled differently by each consumer: the composition plugin counts
it visibly in `unknown_cells`, the minimap aggregator, the CLI renderers and the substitution
plugin swallow it. Because slices 1 and 2 land before any STATE cell can arise at runtime (slice
4), no run is ever affected by that inconsistency; the slice order exists for this reason.

Target: adding a type means one constant in `Config`, one registry line, one entry per side in the
colour/abbreviation palette, plus the entries that are per type by nature (thermodynamic costs and
substitution weight in `reference.conf`, the specification). Everything else derives.

**Backend — single source `MoleculeTypeRegistry`**, extended with order and count (`typeCount()`,
`indexOf(type)`, an ordered list). Its hand-written validator is removed; a unit test walks all
`Config.TYPE_*` fields by reflection and asserts each is registered, so a forgotten line fails the
build without reflection in production code. The class Javadoc's "how to add a type" then holds.

- `GeneSubstitutionPlugin`: weight table sized from the registry; strategy per type as in decision
  point 6; weights and exponents read under the type's name, missing block = weight 0.
- `EnvironmentCompositionPlugin`: columns `<name>_cells` generated from the registry in registry
  order, then `unknown_cells` and `empty_cells`; the positional row array in `extractRows` and the
  chart and percent-base lists are generated from the same list. Existing columns keep their names
  and meaning; `state_cells` is inserted before `unknown_cells`, so the column *order* changes.
  Readers are name-based (Parquet schema, manifest, notebook).
- `MinimapAggregator`: count width from the registry; EMPTY becomes the fixed sentinel byte 255 and
  an unregistered type the sentinel 254 (UNKNOWN) instead of being folded into EMPTY, so a minimap
  byte depends only on the type and a mismatch is visible.
- CLI rendering: one table `MoleculeTypeColors` (type → colour, plus UNKNOWN) used by
  `EnvironmentBackgroundLayer` and `ExactFrameRenderer`. EMPTY and DEAD are not types and stay each
  renderer's own background constants, so neither renderer's output changes. The two tests that use
  the layer's public constants (`LineageRendererTest`, `EnvironmentBackgroundLayerTest`) move to the
  new table deliberately.
- `DecayOnDeath` and `Molecule.getTypeConstantByName` delegate to `MoleculeTypeRegistry.nameToType`;
  the death plugin's error message lists the registry's names instead of a literal list.

**Frontend — single source: one palette keyed by type name.** The frontend already receives the
type names from the run metadata (`moleculeTypes`, type value → name, derived from the registry),
and the worker already delivers cells with type names and `'UNKNOWN'` for an id the map lacks.

- New module `MoleculeTypePalette.js`: `{ CODE: {bg, text, abbr}, DATA: …, STATE: …, UNKNOWN: … }`
  plus the two non-type colours EMPTY (a pixel holding no molecule) and the no-data background,
  which `MinimapRenderer` keeps apart today and continues to.
- `EnvironmentGrid`: the name-to-index table, the colour switches and `getTypeName()` are replaced
  by palette lookups by name.
- `MinimapRenderer`: byte → name through the metadata map, 255 → EMPTY, 254 → UNKNOWN, colour from
  the palette.
- `ValueFormatter`: the type abbreviation comes from the palette (`abbr`): `C`, `D`, `E`, `S`, `L`,
  `LR`, `R`, `ST` for CODE, DATA, ENERGY, STRUCTURE, LABEL, LABELREF, REGISTER, STATE. Today
  LABEL and LABELREF both show as `L:`.
- `AppController`: the type constants `typeCode`…`typeRegister` and the per-type colour entries
  leave the configuration; colours that are not per type stay.

The frontend has no automated tests; after slice 2 the palette is protected only by the visual
check, and a later type addition is again a manual check.

## Changes

| Area | File | Change |
|---|---|---|
| Type | `src/main/java/org/evochora/runtime/Config.java` | `TYPE_STATE = 0x07` |
| Type | `src/main/java/org/evochora/runtime/model/MoleculeTypeRegistry.java` | register `STATE`; order and count; validator removed; Javadoc |
| Type | `src/main/java/org/evochora/runtime/model/Molecule.java` | name resolution delegates to the registry; static compatibility predicate; static *stored form* function |
| Write rule | `src/main/java/org/evochora/runtime/isa/instructions/EnvironmentInteractionInstruction.java` | both write paths (POKE path, PPK path) call the stored-form function instead of their duplicated marker rule |
| Write costs | `src/main/java/org/evochora/runtime/thermodynamics/impl/UniversalThermodynamicPolicy.java`, `PokeThermodynamicPolicy.java` | the molecule to price is the stored form of the operand, not the raw operand; both policies price a type without a rule and without a `_default` block at nothing, base costs only for the universal policy |
| Fork rule | `src/main/java/org/evochora/runtime/isa/instructions/StateInstruction.java` | `FORK`, `FRKI`, `FRKS` fail first thing with MR 0; `SMR*`/`CMR*` operand check uses the predicate |
| Compatibility | `ArithmeticInstruction.java` (two sites), `BitwiseInstruction.java` (type check and shift amount), `ConditionalInstruction.java` | replace the type-equality and DATA-only checks with the predicate |
| Hash | `src/main/java/org/evochora/runtime/model/GenomeHasher.java` | exclude STATE instead of DATA; class and method Javadoc (lines 16–17, 52–53) describe soma and germline |
| Mutation | `src/main/java/org/evochora/runtime/worldgen/GeneSubstitutionPlugin.java` | weight table from the registry; per-type strategy with value perturbation as the general case; no silent return |
| Mutation | `src/main/java/org/evochora/runtime/worldgen/GeneInsertionPlugin.java` | no change: writes DATA operands without a marker register, and they must stay DATA |
| Death plugin | `src/main/java/org/evochora/runtime/worldgen/DecayOnDeath.java` | name switch, Javadoc type list and error message replaced by the registry |
| Defaults | `src/main/resources/reference.conf` | STATE rows in read and write rules; the "cost model" comment block; all types in the substitution defaults with weight and exponent; the substitution comment block |
| Experiment config | `config/evochora.conf` | STATE rows in the read and write rules of the thermodynamic policies; the substitution block lists no STATE block, and none for ENERGY or STRUCTURE either, so those types keep weight 0 |
| Test fixtures | `src/testFixtures/java/org/evochora/test/utils/SimulationTestUtils.java` | STATE write rule in the fixture's thermodynamic configuration, so a test prices a marker-0 write as production does |
| Primordial | `assembly/primordial/lib/energy.evo`, `assembly/primordial/lib/reproduce.evo` | `.PLACE STATE` for the five state slots |
| Analytics | `src/main/java/org/evochora/datapipeline/services/analytics/plugins/EnvironmentCompositionPlugin.java` | columns, row array and chart lists from the registry; class Javadoc metric list corrected |
| Analytics docs | `src/main/java/org/evochora/datapipeline/api/analytics/IAnalyticsPlugin.java` (line 297), `notebooks/data_analysis_guide.ipynb` | type and column lists |
| Minimap | `src/main/java/org/evochora/node/processes/http/api/visualizer/MinimapAggregator.java` | width from the registry, sentinels 255 (EMPTY) and 254 (UNKNOWN); Javadoc |
| API contract | `src/main/proto/org/evochora/datapipeline/api/contracts/http_api_contracts.proto` | comment on `cell_types` describes the registry index plus the two sentinels |
| CLI rendering | new `MoleculeTypeColors`, `EnvironmentBackgroundLayer.java`, `ExactFrameRenderer.java`, `LineageRendererTest`, `EnvironmentBackgroundLayerTest` | shared type colours; EMPTY/DEAD stay per renderer |
| Frontend | new `MoleculeTypePalette.js`, `EnvironmentGrid.js`, `ui/minimap/MinimapRenderer.js`, `utils/ValueFormatter.js`, `AppController.js` | palette by name with abbreviations; index tables, colour switches and per-type colour config removed |
| Docs | `docs/ASSEMBLY_SPEC.md` | molecule types (line 27 ff.), registers hold any type (63), marker classes (43, 85, `FORK` 468, `SMR*`/`CMR*` 471/473 "DATA or STATE"), `POKE`/`PPK` conversion and pricing (444–448), write-rule type lists (146, 448), arithmetic and type propagation (315, 370), strict typing and compatibility, STATE literals, `SNT*` exactness, the three meanings of "state" |
| Docs | `docs/SCIENTIFIC_OVERVIEW.md` | §2.1 type list, §2.3 one paragraph on soma and germline; wording proposed hunk by hunk |
| Docs | `.claude/skills/analyze-run/SKILL.md` | founder-mutation diff excludes STATE, not DATA |
| Docs | `docs/proposals/ideas/LOCAL_STATE.md` | the comparison table no longer claims grid state is mutable by the operators at default weights; directives should save and restore the marker register around a store |

All new public members (registry accessors, the two `Molecule` functions, `MoleculeTypeColors`)
carry complete Javadoc describing what they are, not what changed.

### Tests

New:

- `MoleculeTypeRegistryTest`: STATE registered and resolvable by name; order and count; the
  reflection test over `Config.TYPE_*`.
- `Molecule` tests: stored form (DATA with MR 0 → STATE; DATA with MR ≠ 0, and every other type,
  unchanged; `CODE:0` marker rule preserved); compatibility predicate.
- `EnvironmentInteractionInstruction` tests: the `PPK` swap path and the `POKE` path store the same
  form for the same input.
- Thermodynamics: a DATA register written with MR 0 is priced by the STATE write rule.
- `FORK`/`FRKI`/`FRKS` with MR 0 fail before any energy is taken or an organism is created.
- Arithmetic, bitwise and conditional tests: STATE register against DATA immediate and against DATA
  register succeeds; result type follows the first operand; shift by a STATE amount and `SMR` from a
  STATE register succeed; `IFT` remains strict; other type pairs still fail.
- `GenomeHasherTest`: DATA changes the hash, STATE does not; existing `testDataMoleculesIgnored`
  becomes a STATE test.
- `GeneSubstitutionPlugin` tests: STATE cells are never selected at the configured weight 0; a
  missing type block means weight 0; a configured STATE weight > 0 perturbs the value.
- `EnvironmentCompositionPlugin` test: `state_cells` present and counted; column set and row array
  derived from the registry.
- Consolidation: the registry count drives plugin, composition and aggregator; UNKNOWN reaches the
  minimap as 254; the CLI colour table covers every registered type.
- Compiler: `AssemblyProgramsCompileTest` covers the primordial with `.PLACE STATE`.

Existing tests to revisit: ten files write DATA through `POKE`/`POKI`/`POKS`/`PPKR`/`PPKI`/`PPKS`
without setting a marker (`SimulationTest`, `EnvironmentInteractionInstructionCompilerTest`,
`SimulationEngineIntegrationTest`, `ConflictLossSemanticsTest`, `EnergyCostTest`,
`DeterministicExecutionTest`, the resume `StatefulProgram`, `VMEnvironmentInteractionInstructionTest`
(line 349 asserts DATA), `PokeThermodynamicPolicyTest`, `UniversalThermodynamicPolicyTest`), and
`VMStateInstructionTest.testFrks` forks with MR 0 and asserts success. Each is decided individually:
tests of the write or fork itself set a marker and keep their assertion; tests of the new rules
expect the new behaviour; tests that compare runs against each other are unaffected. The suite is
searched once for any other `FRK*`/`FORK` execution without a preceding `SMR*`. A test that
provokes the unknown-type warning in a thermodynamic policy declares it with `@ExpectLog`.

The grandchild-level invariant (three equal hashes across parent, child and grandchild of the
primordial) is verified once by the throwaway harness of slice 0 and is not regression-protected: a
permanent test of that shape does not fit the test-time budget, and the primordial is not part of
the test suite. The harness was deleted after slice 5.

Verification: `./gradlew check` in the worktree; JMH tick benchmark before merge. The benchmark's
ENVIRONMENT and REALISTIC programs already execute `POKI`/`PPKI` at MR 0, so the new write branch is
measured without a benchmark change.

## Implementation plan

Six vertical slices. Each ends with a green `./gradlew check` and one statement that can be
checked; each is its own commit whose message states the verification result. The safety net comes
before the first behavioural change, so that every later slice fails visibly instead of silently.
The specification changes travel with the slice that changes the behaviour, so every commit stands
on its own.

| # | Slice | Content | Verification | What it catches early |
|---|---|---|---|---|
| 0 | Safety net | A throwaway replication harness, never committed: an untracked JUnit class in the implementing worktree, run through the test filter (`./gradlew test --tests '*PrimordialReplicationHarness*'`), deleted when the work is done. It compiles the primordial with the compiler API, places it into an `Environment` with the same ~25 lines the engine uses (copied into the harness, no production code touched), runs a `Simulation` with a deterministic seed in a toroidal world large enough that parent, child and grandchild cannot overlap through wrapping (1024 × 512 cells), with ENERGY placed within reach, until a grandchild is born. Asserts: the three genome hashes are equal and the cell types are as today (slots DATA). No queues, persistence or indexers, no Gradle change. If the harness turns out harder than this, it is dropped and the visual check takes its place | harness green on unchanged code, runtime seconds | Nothing yet; from here on every slice breaks visibly. The grandchild matters: only the child computes with STATE registers in `INIT`, harvest and reproduction |
| 1 | The type exists, nothing uses it | `TYPE_STATE`, registry with order and count, validator replaced by the reflection test, name resolution in `Molecule` and `DecayOnDeath` through the registry, thermodynamic STATE rows with comments (inert until slice 4), `.PLACE STATE` compiles | registry tests, compiler test with a STATE literal, existing suite unchanged | registration and parsing problems, isolated from any semantics |
| 2 | Consolidation | substitution strategies and weights per type from configuration, composition columns from the registry, minimap sentinels, `MoleculeTypeColors`, frontend palette with abbreviations; no behavioural change for existing types; `config/evochora.conf` line; proto comment, notebook, Javadoc mirrors | consolidation tests; composition columns for existing types keep their names; a rendered frame of a world with a `.PLACE`d STATE cell through the CLI renderer, and a screenshot of the visualizer showing the same cell, both checked by the reviewing agent | refactoring errors in the display before STATE cells arise at runtime; the frontend has no tests, so the visual check belongs here, not at the end |
| 3 | Compatibility | the predicate in `Molecule`, used at the seven sites; ASSEMBLY_SPEC hunks for compatibility, type propagation, `SMR*`/`CMR*`, `SNT*` | unit tests per site; `IFT` stays strict; other type pairs still fail; slice 0 unchanged green because no STATE cell exists yet | ALU errors in isolation; the rule is in place before STATE circulates |
| 4 | The behavioural change | the stored-form function, both write paths and both thermodynamic policies using it, `FORK` with MR 0 fails, hasher excludes STATE instead of DATA, the `.PLACE STATE` lines in the primordial; the eleven existing tests adapted one by one; ASSEMBLY_SPEC hunks for marker classes, write rule and pricing, `FORK`, STATE literals | unit tests for stored form, pricing and fork rule; hasher tests; slice 0 with changed asserts: slots STATE, operands DATA, three equal hashes | whether the compatibility covers every primordial path: if the grandchild does not run or the hashes differ, the fault is confined to this slice. Write rule and hash switch have to land together; either alone turns slice 0 red by construction |
| 5 | Documentation and measurement | SCIENTIFIC_OVERVIEW (hunks for approval), analyze-run skill, LOCAL_STATE note; JMH tick benchmark; one verification run of the primordial over a few generations | benchmark on the benchmark host with determinism check; in the run: siblings with equal hashes, `state_cells` > 0, DATA substitutions visible as mutations, using the body-diff analysis of run `20260902` | shows that the goal is reached, not only that the code runs. Run and benchmark are announced with their duration and wait for the go |

At three points the implementing agent offers the maintainer an additional check with the standard
configuration, the full pipeline and the primordial, and names what to look for:

- after slice 2: in the visualizer, a `.PLACE`d STATE cell shows the new colour in grid and
  minimap, all other types look as before, the environment composition chart has a `state_cells`
  series at zero;
- after slice 4: in a short run, the state slots of every organism (rows 10 and 33 of the body,
  cells 1–3 and 1–2) show the STATE colour after the first store, all other cells keep their
  types; siblings of one parent carry the same genome hash; no organism dies at birth from a
  failed `FORK`;
- after slice 5: in the verification run, `state_cells` grows with the population, clade charts
  show more genomes than a comparable old run, and a DATA substitution appears as its own clade.

Slice 3 must precede slice 4: otherwise the child computes `ANDR %MASK %FWD_MASK` across a DATA and
a STATE register against a strict ALU. Slice 2 follows slice 1 because the registry extension is
its source. The short simulation runs in slices 2 and 5 are proposed with purpose, duration and
data directory before they start.

## Consequences

- **New runs.** A run is resumed with the build that created it; the new hash applies to runs
  started with the new build.
- **Metrics keep their names and change their meaning.** `genome_diversity.total_genomes`,
  `shannon_index` and `dominant_share` count genomes that differ in DATA operands, which the old
  hash merged; `environment_composition.data_cells` drops by the state slots, which move to
  `state_cells`. Values from runs before and after the change are not comparable. Memory is bounded
  as before: distinct living genomes cannot exceed living organisms.
- **Analytics.** More distinct genomes, finer clade charts; DATA substitutions become visible
  mutations.
- **A changed observable.** A mutant that loses its `SMRI DATA:1` used to hand its body to the
  child at the next fork; it now fails the fork, which appears in the instruction failure counts and
  in the organism inspector.
- **Performance.** One additional branch per environment write and one predicate call per typed
  scalar operation, both outside the per-tick scan and per-cell paths; no measurable effect is
  expected, to be confirmed by the JMH tick benchmark before merge.
- **Determinism.** Every new rule is a pure function of organism state and written value; the
  marker register is part of the serialised organism state.
- **Follow-up.** After this proposal is implemented, an issue proposes value-based typing: all
  scalar comparisons and arithmetic on the value alone, result type from the first operand. That
  would remove the DATA ≡ STATE special case and make the rule uniform, but it needs its own
  preparation — splitting the `STRICT_TYPING` flag (the NOP treatment of non-CODE cells in the IP
  path and the compiler's typed literals must stay), hardening the primordial's row terminator test
  (`IFR %TMP %SHELL` currently relies on typed equality; the KLEFT slot passes through the value 100
  and lies in a copied row), and a fertility comparison before and after.
- **Follow-up.** A permanent primordial replication test in place of the throwaway harness: a
  test-owned configuration, organism placement offered as a runtime utility instead of copied from
  the engine, and the state slots to inspect derived from the program artifact.

### Documented limits

- An organism that overwrites its own DATA operands with marker 0 turns them into STATE. That is
  the definition working as intended for self-modifying code; for a roaming data pointer that
  happens to hit its own code it is a loss of visibility. CODE and REGISTER cells are unaffected,
  so the loss of such cells stays visible.
- A mutant that loses the `SMRI DATA:0` before its state store writes state with marker 1. Those
  cells are DATA, are in the hash, and are handed to the child at the next fork, so the lineage shows
  sibling noise again. The mutant also loses its state slots at every fork and then writes into
  foreign cells at foreign-read cost; the defect is strongly deleterious and self-limiting.
- `SNT*` scans match the type exactly: a scan for DATA does not find STATE cells. That is
  deliberate; `SNTI %M STATE:0` finds them.
- `IFT*`/`INT*` compare types strictly; a STATE register does not type-match a DATA literal.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Do nothing | The one class of variation that produces graded phenotypes stays invisible; decisions about mutation regimes are made blind |
| Hash DATA in operand spans only (positional rule) | Correct for the shipped primordial, wrong for a primordial that keeps behaviour in a DATA table; encodes one layout into the physics |
| Make the hash rule run-configurable | In a world with several primordials no single rule fits; every primordial author would have to ship a matching hash definition |
| Include all DATA in the hash | Siblings inherit state slots as of copy time and would differ; every birth looks like a mutation |
| Normalise STATE to DATA on load (`SCAN`/`PEEK`) instead of ALU compatibility | The copy loop carries cells through registers and the data stack; the child's slots would arrive as DATA and siblings would differ again |
| A flag bit instead of a type | The molecule has 4 marker, 8 type and 20 value bits and no spare bit; a flag would cost a type or value bit and be a type with a worse encoding |
| Type the constants instead of the state (compiler emits operands as a CONST type) | Symmetric, but the migration is far larger: compiler, specification, every `.evo` file, every test with DATA operands, every instruction that demands DATA |
| STATE type with STATE literals required in the code under strict typing | The compared constants (thresholds, harvest periods) would themselves become STATE, excluded from hash and mutation, defeating the purpose |
| STATE as a storage type converted by a cast instruction in the state macros | The cast is a mutable instruction that would need excluding in insertion and substitution, and a forgotten cast fails silently |
| Explicit store-as-STATE and load-as-DATA instruction variants | Keeps strict typing intact and leaves the choice to the author, but the choice is then a mutable instruction, and a forgotten variant fails silently. Remains available as an *addition* later: a STATE-forcing store needs no exception in the rule, a DATA-forcing store with marker 0 would be one flag in the stored-form function |
| Convert DATA to STATE when overwriting an own cell | Fails for clear-then-write and for scratch cells written next to the body; depends on how a program writes, not on what it means |
| Put the write rule in `Environment.setMolecule` | Its callers include resume, seeding, the mutation plugins and the energy creators, none of which has a writing organism or a marker register, and a resume that converted restored DATA would break replay |
| Put the write rule in a plugin or the instruction interceptor | There is no plugin hook on the write path, and the interceptor is optional per configuration; physics that can be switched off is not physics |
| Price writes by the register type and drop the STATE write rows | Consistent too, but the configuration would then describe a rule the policy never applies; pricing the stored form keeps write and read rules symmetric |
| Fail at start when a type without a mutation strategy has a non-zero weight | Made unnecessary: every type has a strategy, the value perturbation being the general case |
| Reject STATE literals in instructions at compile time | Would only catch a mistake, at the price of removing a legitimate tool (a constant protected from substitution, a scan target); rejected, and no warning either |
| Disable strict typing altogether | The flag bundles three things: NOP treatment of non-CODE cells in the IP path (`VirtualMachine`), typed literals in the compiler, and operand type equality. The first two must stay; and the primordial's row terminator test relies on typed equality. Taken up as the follow-up above |

## Decisions taken

- Type name `STATE`, value `0x07`.
- The write rule keys on the writer's marker register, not on cell ownership or the target's marker,
  and lives in one stored-form function used by the instructions and the thermodynamic policies.
- Write costs follow the stored form.
- Marker 0 is the ephemeral class by definition; `FORK` with marker register 0 fails, checked before
  energy is taken or a child is created.
- Compatibility covers DATA and STATE in arithmetic, bitwise operations, value comparisons, shift
  amounts and `SMR*`/`CMR*` operands, as one static predicate; strict typing stays for all other
  pairs, for type comparisons and for type scans.
- STATE literals in instructions are allowed and documented; no compiler error, no warning.
- Every registered type has a substitution strategy and a configurable weight; value perturbation
  is the general strategy; defaults 0 for ENERGY, STRUCTURE and STATE; a missing block is weight 0.
- STATE thermodynamic costs equal DATA costs, written as plain rows without explanatory comments.
- Both thermodynamic policies price a type without a rule and without a `_default` block at
  nothing; for the universal policy the instruction's base costs remain.
- In the frontend palette STATE shares the DATA background and is told apart by amber text, the
  way LABELREF is told from LABEL by its text colour; the CLI colour table, which has one colour
  per type and draws no text, gives STATE the DATA colour.
- The specification carries strict typing and the DATA/STATE compatibility once, in §2
  "Molecules" where the types are introduced; the instruction entries only name the operand
  types they accept.
- No change to `GenomeHasher`'s signature; no direction-vector parameter; marker bits stay part of
  the hashed value.
- Type enumerations are consolidated to one source per side as part of this change; the registry
  validator becomes a reflection test; minimap sentinels 255 (EMPTY) and 254 (UNKNOWN); CLI EMPTY
  and DEAD colours stay per renderer.
- The primordial replication harness is throwaway; no permanent primordial test.
- Value-based typing is a separate follow-up issue, not part of this change.
