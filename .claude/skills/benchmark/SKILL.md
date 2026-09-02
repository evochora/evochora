---
name: benchmark
description: Measure the performance effect of a code change in Evochora — JMH tick benchmark for the isolated hot path, real-run comparison for production behaviour, both on the benchmark host with determinism verified. Use when asked to benchmark, measure, or verify a performance change.
---

# Benchmarking a change

Two instruments exist. They answer different questions, and a decision about a hot-path change
normally needs both. Conditions, validity criteria and how to interpret a difference are in
`docs/BENCHMARKING.md`; read it first and do not restate it here — this skill is the procedure.

| | JMH tick benchmark | Real-run comparison |
|---|---|---|
| Measures | `Simulation.tick()` throughput on synthetic programs, no deaths, nothing persisted | wall time of a real node: primordial, thermodynamics, mutation, births and deaths, sampling |
| Answers | did the instruction path get faster | does production get faster, and does behaviour stay identical |
| Cost | ≈13 min per side (decision profile) | ≈9 min per side per 10 M ticks, plus 3 min quiet-wait each |
| Blind to | population dynamics, allocation on the sampling path, pipeline threads | nothing in the engine — but noisier per run (0.5 % between rounds on the host) |

Both run on the benchmark host (`BENCH_SSH`, default `ubuntu@evochora.org`) in throw-away
containers from a digest-pinned JRE image. Never on a developer machine while anything else runs,
never two measurements at once, never from a working tree with uncommitted changes.

## 0 · Before measuring

1. Name both sides as commits: base and candidate. Merge `origin/main` into the candidate first,
   or the comparison measures somebody else's change too.
2. Say what the change should do to the numbers **before** running: which combinations move,
   in which direction, roughly how much. A prediction that fails is the most useful result.
3. Check the host is free: `ssh $BENCH_SSH uptime` — another session may be measuring. Both
   scripts refuse a loaded host, but a queued run still collides with theirs.

## 1 · JMH tick benchmark

Build each side from a clean checkout — `git archive <commit> | tar -x -C <dir>`, then
`./gradlew jmhJar --no-daemon` in that directory. Then, per side:

```bash
BENCH_JMH_ARGS="SimulationBenchmark.tick -p parallelism=4 -f 3 -wi 3 -i 8 -jvmArgsAppend -Xms8g" \
    tools/bench-server/run-benchmark.sh <side>/build/libs/evochora-latest-jmh.jar <side>.json
```

That is the decision profile (three forks, eight iterations, pre-sized heap, ≈13 min); the class
defaults are for quick looks. Compare the two JSON files combination by combination: score and
error of both sides, relative difference, and whether the 99.9 % confidence intervals overlap.
A difference inside the combined error is "within error", not a small gain. Look at the pattern
before the numbers: a change on the `CALL`/`RET` path moves every `PROC_CALL` population alike;
a drop in a single combination is the environment.

`-p selectionSpread=50` matches production (weighted-random label choice, one random draw per
jump); the default `0` is deterministic and does not exercise the organism's random source.

## 2 · Real-run comparison

The node starts with two services only: the simulation engine and `TickHashConsumer`
(`tools/bench-server/consumer/`), which drains every chunk from an in-memory queue and folds its
bytes — run id and capture times cleared — into a running FNV-1a hash. Nothing is persisted. The
engine pauses itself at `pauseTicks`, so every variant does identical work. Two results per
variant: the wall seconds between the log lines `SimulationEngine started` and
`auto-paused at tick`, and the last `TICKHASH` line.

**Identical hashes on both sides are the proof that the change is behaviour-preserving.**
Different hashes mean the change altered the simulation — a bug, or an intended semantic change
that must be named as such — and the timing comparison is meaningless until that is understood.

Setup on the host, under `~/bench/cmp/`:

```
trees/<variant>/       build/install/evochora of a clean checkout (./gradlew installDist)
consumer-classes/      javac -cp "<tree>/lib/*" -d consumer-classes tools/bench-server/consumer/*.java
config/                perf_server.conf + the evochora.conf it includes (same for all variants)
run-comparison.sh      tools/bench-server/run-comparison.sh
```

Run all variants in one invocation, then again in the opposite order:

```bash
./run-comparison.sh base candidate && ./run-comparison.sh candidate base
```

Two rounds with swapped order separate the change from drift. Read `progress.txt`: seconds and
hash per variant. With 0.5 % spread between rounds on the host, a difference of a few percent is
real; report it against the base spread, not as a single number.

`pauseTicks = [10000000]` is the standard length: the population grows past the parallelism
thresholds and deaths accumulate in the organism list, which is where several past changes made
their difference. 1 M ticks is a smoke test, not a measurement.

### When the hashes differ

Set `dumpDir` in the consumer's options and run both sides again. Every normalized chunk lands as
`chunk_<seq>_<lastTick>.pb`; compare the directories file by file, decode the first divergent
chunk with `ChunkDump` (`java -cp "consumer-classes:lib/*" org.evochora.bench.ChunkDump <file>`)
and read the differing field: organism, tick, field name lead to the code line. A divergence in
a dead organism's last instruction record is observation, not behaviour — that has happened.

## 3 · Verdict

- JMH within error and hashes identical: the change is neutral; say so, do not hunt for a gain.
- JMH faster, real run within its spread: the hot path improved but production does not notice
  — report both, the decision is the user's.
- Real run slower although JMH is neutral or faster: the change costs something JMH is blind to
  (allocation, cache locality across ticks, thread hand-over). Dynamic work distribution in the
  worker pool lost 4–5 % this way while its own spin time fell; static assignment keeps each
  organism's state hot in one core's cache.
- Keep the measured tables with the pull request or the note that decided; absolute numbers are
  not a project-wide reference (`docs/BENCHMARKING.md`, Reporting).

## Pitfalls that have cost sessions

- The host is shared: another session's comparison in `~/bench/cmp/` is destroyed by starting a
  second one. Check `uptime` and `progress.txt` before touching the directory.
- A JMH run on a laptop without a clock cap reports throttling as a regression (a "−25 %" once).
  Never conclude from a developer machine without the conditions in `docs/BENCHMARKING.md`.
- Two divisions per dimension in a distance computation cost more on the host's ARM cores than
  the hash-map lookup they replaced (−1.9 %); one division per dimension gave −4.3 %. Micro-
  optimizations need the measurement, not the intuition.
- A synthetic scenario can mislead the profile: 512 clones without label rewriting spent 38 % in
  label matching, which production (labels rewritten at every birth) never does. Profile
  production runs, benchmark synthetic ones.
- Killing a process by a command-line pattern matched the session's own shell. Match only
  processes whose `argv[0]` ends in `/bin/java`.
