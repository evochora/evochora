# Benchmarking

This document describes how to run the JMH benchmarks and, more importantly, how to obtain
numbers that actually support a conclusion. Benchmark results that were measured under unknown
or unstable conditions are worse than no results: they look authoritative and lead to wrong
decisions.

## What is measured

`src/jmh/java/org/evochora/runtime/SimulationBenchmark.java` measures the throughput of
`Simulation.tick()` in ticks per second. Each measurement iteration starts a fresh simulation
with `organisms` copies of one assembly program and runs ticks back to back.

| Parameter | Values | Meaning |
|---|---|---|
| `assembly` | `REALISTIC`, `PROC_CALL` | `REALISTIC` is a general instruction mix (arithmetic, conditionals, environment access, jumps). `PROC_CALL` is a tight `CALL`/`RET` loop with `REF` and `VAL` parameters and stresses the procedure-frame path. |
| `organisms` | `100`, `500`, `2000` | Population size; larger values shift the profile from per-tick overhead towards per-organism work and cache pressure. |
| `parallelism` | `4` (default) | Threads executing the parallel wave of a tick, the main thread included (`1` = main thread alone). Override on the command line (see below). |

The benchmark deliberately isolates the instruction-execution hot path:

- Thermodynamic costs are zero, so no organism dies during a measurement.
- A fixed `SeededRandomProvider` is installed; the benchmark does not measure random-number
  contention.
- Nothing is persisted or serialized.

Consequently the benchmark says nothing about the data pipeline, persistence, or the indexer,
and a change in those areas cannot be validated with it.

## Benchmarks are relative measurements

An absolute number from this benchmark has no meaning on its own. It depends on the CPU, its
clock behavior, the JDK build, the memory situation, and on what else the machine was doing at
the time. Numbers from different machines, different days, or different environment states are
not comparable, and a table of historical absolute values is not a reference.

The only valid use is a **before/after comparison** where both sides are measured

- on the same machine,
- in the same session, one directly after the other,
- under identical conditions (see below),
- from clean checkouts of the exact commits being compared.

"Clean checkout" means the benchmark jar is built from a tree that contains nothing but the
committed state, for example via `git archive <commit> | tar -x -C <dir>`. Never benchmark a
working directory that contains uncommitted changes, and never mix a jar built from one tree
with a comparison run from another.

## Required conditions

The simulation saturates its worker threads for the whole run. On most modern CPUs, and on
every laptop, this triggers turbo-boost and thermal-throttling behavior: the first seconds run
at a high clock, the clock then drops as the package heats up, and the measured throughput
decays over the course of a fork. Such a run produces a mean that depends on the cooling state
of the machine, not on the code.

Before measuring:

1. **Pin the CPU clock.** Disable turbo boost or cap the maximum frequency at a value the
   cooling sustains indefinitely under full load on all benchmark threads. How this is done is
   platform specific (`cpupower frequency-set -u <freq>` with `intel_pstate` on Linux, BIOS
   turbo settings, vendor tools on other systems). A capped clock lowers every absolute number;
   that is irrelevant for a relative comparison and is the point of the exercise.
2. **Use the performance power profile** of the operating system so that the governor does not
   reduce the clock below the cap for power-saving reasons.
3. **No swap in use** and enough free memory for the `-Xmx8g` heap configured in
   `build.gradle.kts`. Swap activity during a run shows up as tens of percent of error.
4. **No competing load.** Stop the Gradle daemon, IDE language servers, browsers with active
   tabs, and anything else that consumes CPU. Check the load average before starting.
5. **Never run two benchmarks at the same time**, not on the same machine, not in parallel
   worktrees, not from parallel agents. They compete for cores, caches, and memory bandwidth and
   invalidate each other.
6. **Same JDK** for both sides of a comparison.

Record clock cap, power profile, JDK version, and the machine in the report so that the
comparison can be repeated.

## Running

Build the benchmark jar and run it directly; this avoids Gradle overhead and gives full control
over the JMH parameters:

```bash
./gradlew jmhJar --no-daemon
java -Xmx8g -jar build/libs/evochora-latest-jmh.jar SimulationBenchmark.tick \
    -p parallelism=1,4 -rf json -rff results.json
```

`-p name=value[,value]` overrides any `@Param` of the benchmark class. Use it to select the
parallelism levels and to restrict a run to a single program or population
(`-p assembly=PROC_CALL -p organisms=2000`) while investigating.

`./gradlew jmh` runs the full matrix with the class defaults and is fine for a quick look, but
the `@Param` values cannot be overridden that way.

The class defaults are two forks, two warmup iterations of three seconds, and five measurement
iterations of three seconds. Keep them for comparisons; a single fork is not a measurement.

While the benchmark runs, observe CPU frequency and temperature (on Linux:
`/sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq` and
`/sys/class/thermal/thermal_zone*/temp`, sampled every few seconds). A frequency that varies
during the run or a temperature that climbs towards the throttling point means the conditions
above are not met.

## Validity criteria

Discard a run, fix the environment, and measure again when any of the following holds:

- The reported error (99.9 % confidence interval) exceeds 10 % of the score for any parameter
  combination. Under stable conditions the benchmark achieves 1–4 %.
- Iterations within a fork fall monotonically. This is the signature of thermal throttling or
  of a background process ramping up, not of a steady state.
- The two forks of one combination differ by more than their combined error.
- The CPU frequency was not constant during the run.

Do not rescue such a run by averaging, dropping forks, or reporting only the first iterations.

## Interpreting a comparison

A difference between before and after is a regression or an improvement only if it exceeds the
combined error of both measurements. Differences inside the error bars are noise and are
reported as "within error", not as small gains or losses.

Check the *pattern* before the numbers: a change to the `CALL`/`RET` path affects every
`PROC_CALL` population roughly equally. A drop that appears only for one population, or that
grows with the position of the combination in the run order, points to the environment (heat,
memory, a background job) rather than to the code.

## Reporting

Attach the following to the pull request or proposal that relies on the measurement:

- both commits (before and after) and how the trees were obtained,
- JDK version, machine, clock cap, power profile, free memory, swap state,
- the JMH command line,
- one table per side with score and error for every parameter combination, and the relative
  difference with the verdict "within error" where applicable.

The JSON files produced by `-rf json` are working data; the table is the result. Absolute
values belong to the change that was verified with them. They are not maintained as a
project-wide reference, because no later measurement on a different machine or day could be
compared with them.
