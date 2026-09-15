#!/usr/bin/env bash
# run-trace.sh — records a run with the trace consumer (tools/trace/consumer/TraceConsumer.java).
#
# Usage: run-trace.sh [fork options] <outputDir> <ticks> [hocon-line ...]
#   outputDir   directory for the trace tables; created, existing tables are overwritten
#   ticks       without fork options: ticks 0 to ticks-1, rounded down to a multiple of the chunk
#               span; with fork options: at least --fork-from to --fork-from + ticks - 1,
#               rounded outwards to whole chunks
#   hocon-line  HOCON appended to the generated configuration, one setting per argument, e.g.
#               'pipeline.services.simulation-engine.options.environment.shape = [256, 160]'
#
# Fork options - trace a window of a recorded run instead of a new one:
#   --fork-run <runId>        the recorded run (required in fork mode)
#   --fork-from <tick>        first tick the trace must hold (required in fork mode)
#   --config <file>           configuration the run was recorded with, where its storage
#                             resource is defined (default config/evochora.conf)
#   --fork-storage <name>     storage resource in that configuration (default tick-storage)
#
# Environment: EVOCHORA_TREE (default build/install/evochora), TRACE_CONF (default
# tools/trace/trace.conf), TRACE_XMX (default 2g), TRACE_TIMEOUT seconds (default 3600).
#
# The node runs from the project root so that relative source roots resolve, writes its log to
# <outputDir>/node.log, is stopped once the consumer has drained the queue after the engine's
# pause, and leaves nothing behind but the trace directory. A recorded run is only read.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TREE="${EVOCHORA_TREE:-$ROOT/build/install/evochora}"
CONF="${TRACE_CONF:-$ROOT/tools/trace/trace.conf}"
XMX="${TRACE_XMX:-2g}"
TIMEOUT="${TRACE_TIMEOUT:-3600}"
CHUNK=50

usage() {
    sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

FORK_RUN=""
FORK_FROM=""
FORK_CONFIG="$ROOT/config/evochora.conf"
FORK_STORAGE="tick-storage"
while [ $# -gt 0 ]; do
    case "$1" in
        --fork-run) [ $# -ge 2 ] || usage; FORK_RUN="$2"; shift 2 ;;
        --fork-from) [ $# -ge 2 ] || usage; FORK_FROM="$2"; shift 2 ;;
        --config) [ $# -ge 2 ] || usage; FORK_CONFIG="$2"; shift 2 ;;
        --fork-storage) [ $# -ge 2 ] || usage; FORK_STORAGE="$2"; shift 2 ;;
        --*) echo "unknown option $1" >&2; usage ;;
        *) break ;;
    esac
done
FORK=false
if [ -z "$FORK_RUN" ] && [ -z "$FORK_FROM" ] \
        && { [ "$FORK_CONFIG" != "$ROOT/config/evochora.conf" ] || [ "$FORK_STORAGE" != "tick-storage" ]; }; then
    echo "--config and --fork-storage belong to fork mode, which needs --fork-run and --fork-from" >&2
    exit 2
fi
if [ -n "$FORK_RUN" ] || [ -n "$FORK_FROM" ]; then
    if [ -z "$FORK_RUN" ] || [ -z "$FORK_FROM" ]; then
        echo "fork mode needs both --fork-run and --fork-from" >&2
        exit 2
    fi
    if ! [[ "$FORK_FROM" =~ ^[0-9]+$ ]]; then
        echo "--fork-from must be a tick number, got $FORK_FROM" >&2
        exit 2
    fi
    if [ ! -f "$FORK_CONFIG" ]; then
        echo "configuration $FORK_CONFIG not found" >&2
        exit 2
    fi
    FORK_CONFIG="$(cd "$(dirname "$FORK_CONFIG")" && pwd)/$(basename "$FORK_CONFIG")"
    FORK=true
fi

if [ $# -lt 2 ]; then
    usage
fi
mkdir -p "$1"
OUT="$(cd "$1" && pwd)"
TICKS="$2"
shift 2

if [ ! -d "$TREE/lib" ]; then
    echo "no installation tree at $TREE (run ./gradlew installDist)" >&2
    exit 1
fi
if ! [[ "$TICKS" =~ ^[0-9]+$ ]]; then
    echo "ticks must be a number, got $TICKS" >&2
    exit 2
fi
if $FORK; then
    if [ "$TICKS" -lt 1 ]; then
        echo "ticks must be at least 1" >&2
        exit 2
    fi
    # The fork records whole chunks of CHUNK ticks on a grid that starts at the parent's
    # checkpoint. Parent chunks are multiples of the fork's, so the grid is the multiples of
    # CHUNK: the recording begins at --fork-from rounded down to it. The engine pauses on the
    # first tick of the chunk after the last one needed, as in a new run, so that chunk is
    # complete and sent. The first recorded tick is checked against the engine's own window.
    FIRST=$(( FORK_FROM / CHUNK * CHUNK ))
    PAUSE=$(( FIRST + (FORK_FROM + TICKS - FIRST + CHUNK - 1) / CHUNK * CHUNK ))
    LAST=$(( PAUSE - 1 ))
    echo "fork of $FORK_RUN: recording ticks $FIRST to $LAST"
else
    if [ "$TICKS" -lt "$CHUNK" ]; then
        echo "ticks must be at least $CHUNK, the span of one chunk" >&2
        exit 1
    fi
    ROUNDED=$(( TICKS / CHUNK * CHUNK ))
    if [ "$ROUNDED" -ne "$TICKS" ]; then
        echo "ticks rounded down from $TICKS to $ROUNDED, the last complete chunk"
        TICKS=$ROUNDED
    fi
    PAUSE=$TICKS
fi

CLASSES="$OUT/classes"
rm -rf "$CLASSES" "$OUT"/steps.tsv "$OUT"/state.tsv "$OUT"/cells.tsv "$OUT"/run.tsv "$OUT"/artifact_*.json "$OUT"/fork-source.json
mkdir -p "$CLASSES"
javac -cp "$TREE/lib/*" -d "$CLASSES" "$ROOT"/tools/trace/consumer/*.java "$ROOT"/tools/trace/fork/*.java

DATA="$OUT/pipeline-data"
RUNCONF="$OUT/run.conf"

if $FORK; then
    # The run's storage resource, resolved in the configuration it was recorded with: its
    # paths no longer depend on pipeline.dataBaseDir, which points into the output directory.
    SOURCE="$OUT/fork-source.json"
    ( cd "$ROOT" && java -cp "$CLASSES:$TREE/lib/*" org.evochora.tools.trace.ForkSource \
        "$FORK_CONFIG" "$FORK_STORAGE" "$SOURCE" ) || exit 1
    # Everything under DATA is removed at the end; a storage inside it would be removed with it.
    if grep -qF "$DATA" "$SOURCE"; then
        echo "the storage resource $FORK_STORAGE lies inside $DATA, which the trace removes; refusing" >&2
        exit 1
    fi
fi

{
    echo "include required(file(\"$CONF\"))"
    echo "pipeline.dataBaseDir = \"$DATA\""
    echo "pipeline.services.trace-consumer.options.outputDir = \"$OUT\""
    echo "pipeline.services.simulation-engine.options.pauseTicks = [$PAUSE]"
    if $FORK; then
        echo "pipeline.resources.fork-source = $(cat "$SOURCE")"
    fi
    for line in "$@"; do
        echo "$line"
    done
} > "$RUNCONF"

if $FORK; then
    NODE_ARGS=(node fork --run "$FORK_RUN" --from "$FORK_FROM" --to "$LAST" --storage fork-source)
else
    NODE_ARGS=(node run)
fi

LOG="$OUT/node.log"
rm -f "$LOG"
(
    cd "$ROOT"
    exec java -Xmx"$XMX" -cp "$CLASSES:$TREE/lib/*" org.evochora.cli.CommandLineInterface \
        --config "$RUNCONF" "${NODE_ARGS[@]}"
) > "$LOG" 2>&1 &
PID=$!

# Wait for the engine's pause, then for the first "drained" line the consumer logs after it:
# the engine sends its last chunk before it logs the pause, so a drain after that line means
# every chunk is on disk.
paused_line=""
STATUS=0
fork_checked=false
waited=0
while kill -0 "$PID" 2>/dev/null; do
    if $FORK && ! $fork_checked; then
        forked="$(grep -m1 -o "SimulationEngine FORKED: .*window=\[[0-9]*" "$LOG" 2>/dev/null || true)"
        if [ -n "$forked" ]; then
            fork_checked=true
            engine_first="$(printf '%s' "$forked" | sed 's/.*window=.//')"
            if [ "$engine_first" != "$FIRST" ]; then
                echo "the fork records from tick $engine_first, not from $FIRST as computed; stopping" >&2
                STATUS=1
                break
            fi
        fi
    fi
    if [ -z "$paused_line" ]; then
        paused_line="$(grep -n "auto-paused at tick" "$LOG" 2>/dev/null | head -1 | cut -d: -f1 || true)"
    fi
    if [ -n "$paused_line" ]; then
        if tail -n +"$((paused_line + 1))" "$LOG" | grep -q "TRACE drained lastTick="; then
            break
        fi
    fi
    if grep -q "ERROR" "$LOG" 2>/dev/null; then
        echo "the node logged an error, see $LOG" >&2
        break
    fi
    sleep 1
    waited=$((waited + 1))
    if [ "$waited" -ge "$TIMEOUT" ]; then
        echo "timeout after ${TIMEOUT}s, see $LOG" >&2
        break
    fi
done

if kill -0 "$PID" 2>/dev/null; then
    kill -TERM "$PID" 2>/dev/null || true
    for _ in $(seq 1 30); do
        kill -0 "$PID" 2>/dev/null || break
        sleep 1
    done
    kill -KILL "$PID" 2>/dev/null || true
fi
wait "$PID" 2>/dev/null || true

echo "trace in $OUT"
if $FORK; then
    echo "  fork of $FORK_RUN, first recorded tick $FIRST"
fi
for f in run.tsv steps.tsv state.tsv cells.tsv; do
    if [ -f "$OUT/$f" ]; then
        printf '  %-10s %8s lines  %s\n' "$f" "$(($(wc -l < "$OUT/$f") - 1))" "$(du -h "$OUT/$f" | cut -f1)"
    fi
done
ls "$OUT"/artifact_*.json 2>/dev/null | sed 's/^/  /'
grep "TRACE drained lastTick=" "$LOG" | tail -1 | sed 's/.*TRACE/  /'
if [ -e "$DATA" ]; then
    echo "  the node created $DATA although nothing is persisted; removed"
    rm -rf "$DATA"
fi
rm -rf "$CLASSES"
exit $STATUS
