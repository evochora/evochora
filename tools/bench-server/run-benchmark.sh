#!/usr/bin/env bash
# run-benchmark.sh — runs a JMH benchmark jar on the project's dedicated benchmark
# host and fetches the JSON result.
#
# The benchmark host is a quiet machine with dedicated, fixed-clock CPU cores
# (no turbo, no thermal drift, zero steal time, no swap). The benchmark executes
# in a throw-away container from a digest-pinned JRE image, so the measurement
# environment is identical from run to run. Before starting, the script refuses
# to run if the host shows load or CPU steal, so a busy host can never silently
# produce invalid numbers.
#
# Usage:
#   tools/bench-server/run-benchmark.sh <jmh-jar> <local-result.json>
#
# Environment overrides:
#   BENCH_SSH       ssh target of the benchmark host   (default: ubuntu@evochora.org)
#   BENCH_JMH_ARGS  JMH selector and parameters        (default: full tick sweep, P=4)
#   BENCH_MAX_LOAD  1-min load above which to refuse   (default: 0.5)
#   BENCH_PAUSE_TIMERS
#                   host systemd timers paused for the duration of the run
#                   (default: the host's periodic maintenance timers — cloud
#                   monitoring agent and firmware refresh — whose bursts would
#                   hit single iterations; set to the empty string to skip)
#
# The jar is uploaded under a unique name, the result is copied back, and both
# are removed from the host afterwards — also when the run fails or is aborted.
# Paused timers are restarted by the same exit handler.
# Only relative comparisons between runs on this host are meaningful; see
# docs/BENCHMARKING.md.
set -euo pipefail

HOST="${BENCH_SSH:-ubuntu@evochora.org}"
IMAGE="eclipse-temurin@sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037"
MAX_LOAD="${BENCH_MAX_LOAD:-0.5}"
JMH_ARGS="${BENCH_JMH_ARGS:-SimulationBenchmark.tick -p parallelism=4}"
PAUSE_TIMERS="${BENCH_PAUSE_TIMERS-unified-monitoring-agent_config_downloader.timer fwupd-refresh.timer}"

die() { echo "run-benchmark: $*" >&2; exit 1; }

[[ $# -eq 2 ]] || die "usage: run-benchmark.sh <jmh-jar> <local-result.json>"
jar="$1"
out="$2"
[[ -f "$jar" ]] || die "jar not found: $jar"

SSH=(ssh -o BatchMode=yes -o ConnectTimeout=10 "$HOST")

# --- Guard: refuse a host that is not quiet -------------------------------
"${SSH[@]}" "
    load=\$(cut -d' ' -f1 /proc/loadavg)
    awk -v l=\"\$load\" -v m=\"$MAX_LOAD\" 'BEGIN { exit (l > m) ? 1 : 0 }' \
        || { echo \"host busy: 1-min load \$load > $MAX_LOAD\" >&2; exit 1; }
    s1=\$(awk '/^cpu /{print \$9}' /proc/stat); sleep 3
    s2=\$(awk '/^cpu /{print \$9}' /proc/stat)
    [[ \$((s2 - s1)) -eq 0 ]] \
        || { echo \"host shows CPU steal (\$((s2 - s1)) ticks in 3 s)\" >&2; exit 1; }
" || die "guard failed — benchmark host is not quiet, refusing to measure"

# --- Upload, run in a throw-away container, fetch, clean up ---------------
name="run-$(date +%Y%m%d-%H%M%S)-$$"
cleanup() {
    "${SSH[@]}" "rm -f ~/bench/$name.jar ~/bench/$name.json" || true
    if [[ -n "$PAUSE_TIMERS" ]]; then
        "${SSH[@]}" "sudo -n systemctl start $PAUSE_TIMERS" \
            || echo "run-benchmark: WARNING: could not restart host timers: $PAUSE_TIMERS" >&2
    fi
}
trap cleanup EXIT

# Periodic host maintenance would hit single iterations; pause it for the run.
if [[ -n "$PAUSE_TIMERS" ]]; then
    "${SSH[@]}" "sudo -n systemctl stop $PAUSE_TIMERS" \
        || die "could not stop host maintenance timers ($PAUSE_TIMERS) — set BENCH_PAUSE_TIMERS='' to measure without pausing them"
fi

"${SSH[@]}" "mkdir -p ~/bench"
scp -q -o BatchMode=yes "$jar" "$HOST:~/bench/$name.jar"

uid_gid="$("${SSH[@]}" 'echo "$(id -u):$(id -g)"')"
"${SSH[@]}" "docker run --rm -u $uid_gid -v \$HOME/bench:/bench -w /bench \
    --name bench-$name $IMAGE \
    java -Xmx8g -jar $name.jar $JMH_ARGS -rf json -rff $name.json"

scp -q -o BatchMode=yes "$HOST:~/bench/$name.json" "$out"
echo "run-benchmark: result written to $out"
