# Trace recording

A recorder for debugging programs: it runs the node with the simulation engine and one
consumer, `TraceConsumer`, and writes what every organism executed in every tick, the state it
was left in, and every cell that changed. Nothing is persisted by the pipeline; the trace
directory is the only output. The tables are tab-separated text, meant to be queried with
DuckDB (`duckdb` reads them directly) and searchable with grep.

The consumer is a pipeline service outside the product build, like the benchmark consumer under
`tools/bench-server/`: `run-trace.sh` compiles it against the installation tree and puts it on the
node's classpath.

## Running

```bash
./gradlew installDist                       # once, and after every change to the product
tools/trace/run-trace.sh <outputDir> <ticks> [hocon-line ...]
```

`ticks` is the number of ticks recorded, numbered from 0; it is rounded down to a multiple of
50, the span of one chunk, because the engine does not send a partial chunk when it pauses. Every further
argument is one HOCON line appended to the generated configuration, which is how a task sets its
world, organisms, seed or plugins:

```bash
tools/trace/run-trace.sh /tmp/trace 5000 \
  'pipeline.services.simulation-engine.options.environment.shape = [256, 160]' \
  'pipeline.services.simulation-engine.options.seed = 7'
```

`tools/trace/trace.conf` holds the recording setup and includes `config/evochora.conf` for
everything else. Every process, resource and service the recording does not need is set to
`null` there, which takes it out of the configuration: no brokers, no database, no HTTP server,
no persistence, no indexers; only the engine, the consumer and their two in-memory queues run.
One further deliberate difference: the plugin list is emptied, so there is no world
generation, no decay on death and no mutation, only the label rewriting of newborns. A task that
needs a plugin adds it back on the command line; the comments in `trace.conf` show how. The
node's log lands in `<outputDir>/node.log`, the generated configuration in `<outputDir>/run.conf`.

## Tables

All files have a header line, tab-separated columns and no quote characters. Molecules are
written as `TYPE:value`, with `/marker` appended when the marker is not zero; vectors as `x|y`;
a location register or stack entry that holds no position as `-`; lists as `[a,b,c]`.

**`steps.tsv`** - one row per organism and tick in which it executed an instruction or failed
one (a conflict loser has no executed instruction, only a failure reason).

| column | meaning |
|---|---|
| `tick`, `org` | tick and organism |
| `ip` | absolute position of the executed instruction (the IP before the fetch) |
| `rel` | that position relative to the organism's origin, the coordinate the compiler laid out |
| `addr` | linear address of that coordinate in the program's artifact; empty when the IP is outside the program layout |
| `exec_op` | the instruction actually fetched from the cells, by mnemonic |
| `exec_args_raw` | the argument molecules as read from the cells |
| `exec_args` | the arguments as the instruction resolved them: `%PDR2=1\|0` for a register with the value it held before the instruction, immediates as molecules, vectors as components, labels by name, `S0=...` for a stack operand with the value from the top of the previous tick's data stack. A formal register carries the parameter name the procedure declared for it, `%FDR1(MARKER)=DATA:3`; the machine register comes first because it is what the cell holds, the name only what the compiler once meant by it |
| `dp` | the data pointer the instruction acted with: index and position of the active pointer as the tick began, `1@61\|40` |
| `cost_e`, `cost_s` | energy taken and entropy added by the instruction |
| `failed`, `fail_reason` | 1 and the reason when the instruction failed |
| `cond_met` | for a conditional instruction: 1 when the instruction right behind it ran next, 0 when that one was skipped; empty for every other instruction. A skipped instruction costs no tick and has no row of its own, so this is where a decision shows |
| `src_file`, `src_line`, `src_text` | what the compiled program has at `addr`: file (relative to the directory all sources share), line and the source line |
| `src_label` | the nearest label or procedure above that source line |
| `src_op` | what the compiler placed at `rel`: an opcode by mnemonic, or the molecule type for a non-code cell such as `LABEL` |
| `differs` | 1 when `exec_op` is not what the compiler placed there (a label cell counts as the `NOP` the machine executes it as), or the position is not in the code layout at all: the cell no longer holds what was written |
| `changed` | what the tick changed in the organism, as `name:old>new`: every register and data pointer that changed, the active pointer index, marker, direction, and the depth of each stack. The result of a `SCAN` shows here as the register it wrote, in the same row |

A step row is written when the organism's next step is known, because `cond_met` needs it; with
several organisms the rows are therefore not in tick order across organisms. Order by `tick`.

**`state.tsv`** - one row per organism and tick, the state after the tick. A dead organism
appears once more with `dead = 1` and its `death_tick`.

| column | meaning |
|---|---|
| `tick`, `org`, `dead`, `birth_tick`, `death_tick`, `parent`, `generation`, `program` | identity |
| `origin` | the organism's initial position, the origin of `rel` in `steps.tsv` |
| `er`, `sr`, `mr` | energy, entropy, molecule marker register |
| `ip`, `dv`, `adp` | instruction pointer after the tick, direction vector, active data pointer index |
| `dp0` ... | every data pointer |
| `dr0` ... `slr3` | every register, in bank order: DR, LR, PDR, PLR, FDR, FLR, SDR, SLR |
| `ds`, `ls` | data stack and location stack, top first |
| `cs` | call stack as `PROC@return_ip`, innermost first |
| `genome_hash` | the genome hash |

**`cells.tsv`** - cells by tick. `kind = full` rows are the complete set of occupied cells: at
the first tick and then every `cellSnapshotInterval` ticks (option in `trace.conf`, default 1000).
`kind = change` rows are the cells whose molecule or owner changed in that tick; a cell that
became empty is written as `CODE:0` with owner 0. At a tick where both appear, the change rows
come first and the full rows describe the same state.

| column | meaning |
|---|---|
| `tick`, `kind` | tick and row kind |
| `x`, `y` (`z`; `c0`, `c1`, ... above three dimensions) | coordinates |
| `type`, `value`, `marker`, `owner` | the molecule and the owning organism, 0 for none |

**`run.tsv`** - key/value facts: run id, seed, build revision, world shape, toroidal, program
ids, cell snapshot interval. **`artifact_<programId>.json`** - the compiler artifact of every
program as JSON: sources, source map, labels, placed molecules, register aliases.

## Querying

`tools/trace/views.sql` creates the views `steps`, `state` and `cells` over the files of the
current directory and the table macros `world_at(t)` and `world_end()`:

```
cd <trace directory> && duckdb
.read /path/to/tools/trace/views.sql
```

Step through a window of one organism:

```sql
SELECT tick, src_label, src_text, exec_args, failed, fail_reason
FROM steps WHERE org = 1 AND tick BETWEEN 1200 AND 1300 ORDER BY tick;
```

Where an organism spends its ticks:

```sql
SELECT src_label, count(*) AS ticks
FROM steps WHERE org = 1 AND tick BETWEEN 10000 AND 20000
GROUP BY 1 ORDER BY 2 DESC;
```

First failure of every kind:

```sql
SELECT fail_reason, min(tick) AS first_tick, count(*) AS times
FROM steps WHERE failed = 1 GROUP BY 1 ORDER BY 2;
```

The effect of one step, as the difference to the previous tick:

```sql
SELECT a.tick, s.src_text, b.dp0 AS dp0_before, a.dp0 AS dp0_after, b.er - a.er AS energy_spent
FROM steps s
JOIN state a ON a.org = s.org AND a.tick = s.tick
JOIN state b ON b.org = s.org AND b.tick = s.tick - 1
WHERE s.org = 1 AND s.tick = 12345;
```

Where a register value came from: the last tick before T in which it changed.

```sql
SELECT tick, pdr4 FROM state WHERE org = 1 AND tick <= 12345
QUALIFY pdr4 <> lag(pdr4) OVER (ORDER BY tick)
ORDER BY tick DESC LIMIT 1;
```

The world at tick T, in a rectangle:

```sql
SELECT * FROM world_at(12345) WHERE x BETWEEN 10 AND 40 AND y BETWEEN 10 AND 30 ORDER BY y, x;
```

Did a program copy a region correctly? Compare the region as it was at tick 0, shifted to where
the copy should be, with the world at the end, in both directions; type and value, not the
marker, because a copy is written with the organism's marker register. Then check the total
count of occupied cells against what the copy should have added, which says that nothing was
written anywhere else.

```sql
WITH orig AS (SELECT x, y, type, value FROM world_at(0) WHERE x BETWEEN 40 AND 60 AND y BETWEEN 40 AND 50),
     expect AS (SELECT x + 21 AS x, y, type, value FROM orig),
     actual AS (SELECT x, y, type, value FROM world_end() WHERE x BETWEEN 61 AND 81 AND y BETWEEN 40 AND 50)
SELECT (SELECT count(*) FROM (SELECT * FROM expect EXCEPT SELECT * FROM actual)) AS missing,
       (SELECT count(*) FROM (SELECT * FROM actual EXCEPT SELECT * FROM expect)) AS unexpected,
       (SELECT count(*) FROM world_end()) - (SELECT count(*) FROM world_at(0)) AS cells_added;
```

Which way did the conditionals in a loop go, and what did each step change:

```sql
SELECT tick, src_text, exec_args, cond_met, changed
FROM steps WHERE org = 1 AND src_label = 'FIND_EDGES' ORDER BY tick LIMIT 40;
```

An invariant of the program, as a query that lists its violations: "at CONTINUE_LOOP the data
stack is empty".

```sql
SELECT s.tick, t.ds FROM steps s JOIN state t ON t.org = s.org AND t.tick = s.tick
WHERE s.org = 1 AND s.src_label = 'CONTINUE_LOOP' AND t.ds <> '[]';
```

## What the trace does not hold

- Why a fuzzy jump resolved to the label it did; only where it landed, in the next tick's `ip`.
- The target cell of a world instruction that failed; on success the cell is in `cells.tsv`, on
  failure only the reason is recorded.
- Ticks after the last complete chunk, see `ticks` above.
