#!/usr/bin/env bash
# run-trace.sh — records a run with the trace consumer (tools/trace/consumer/TraceConsumer.java).
#
# Usage: run-trace.sh <outputDir> <ticks> [hocon-line ...]
#   outputDir   directory for the trace tables; created, existing tables are overwritten
#   ticks       the engine pauses after this tick; rounded down to a multiple of the chunk span
#   hocon-line  HOCON appended to the generated configuration, one setting per argument, e.g.
#               'pipeline.services.simulation-engine.options.environment.shape = [256, 160]'
#
# Environment: EVOCHORA_TREE (default build/install/evochora), TRACE_CONF (default
# tools/trace/trace.conf), TRACE_XMX (default 2g), TRACE_TIMEOUT seconds (default 3600).
#
# The node runs from the project root so that relative source roots resolve, writes its log to
# <outputDir>/node.log, is stopped once the consumer has drained the queue after the engine's
# pause, and leaves nothing behind but the trace directory.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TREE="${EVOCHORA_TREE:-$ROOT/build/install/evochora}"
CONF="${TRACE_CONF:-$ROOT/tools/trace/trace.conf}"
XMX="${TRACE_XMX:-2g}"
TIMEOUT="${TRACE_TIMEOUT:-3600}"
CHUNK=50

if [ $# -lt 2 ]; then
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
fi
mkdir -p "$1"
OUT="$(cd "$1" && pwd)"
TICKS="$2"
shift 2

if [ ! -d "$TREE/lib" ]; then
    echo "no installation tree at $TREE (run ./gradlew installDist)" >&2
    exit 1
fi
if [ "$TICKS" -lt "$CHUNK" ]; then
    echo "ticks must be at least $CHUNK, the span of one chunk" >&2
    exit 1
fi
ROUNDED=$(( TICKS / CHUNK * CHUNK ))
if [ "$ROUNDED" -ne "$TICKS" ]; then
    echo "ticks rounded down from $TICKS to $ROUNDED, the last complete chunk"
    TICKS=$ROUNDED
fi

CLASSES="$OUT/classes"
rm -rf "$CLASSES" "$OUT"/steps.tsv "$OUT"/state.tsv "$OUT"/cells.tsv "$OUT"/run.tsv "$OUT"/artifact_*.json
mkdir -p "$CLASSES"
javac -cp "$TREE/lib/*" -d "$CLASSES" "$ROOT"/tools/trace/consumer/*.java

DATA="$OUT/pipeline-data"
RUNCONF="$OUT/run.conf"
{
    echo "include required(file(\"$CONF\"))"
    echo "pipeline.dataBaseDir = \"$DATA\""
    echo "pipeline.services.trace-consumer.options.outputDir = \"$OUT\""
    echo "pipeline.services.simulation-engine.options.pauseTicks = [$TICKS]"
    for line in "$@"; do
        echo "$line"
    done
} > "$RUNCONF"

LOG="$OUT/node.log"
rm -f "$LOG"
(
    cd "$ROOT"
    exec java -Xmx"$XMX" -cp "$CLASSES:$TREE/lib/*" org.evochora.cli.CommandLineInterface \
        --config "$RUNCONF" node run
) > "$LOG" 2>&1 &
PID=$!

# Wait for the engine's pause, then for the first "drained" line the consumer logs after it:
# the engine sends its last chunk before it logs the pause, so a drain after that line means
# every chunk is on disk.
paused_line=""
waited=0
while kill -0 "$PID" 2>/dev/null; do
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
