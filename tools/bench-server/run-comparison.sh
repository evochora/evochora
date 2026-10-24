#!/usr/bin/env bash
# run-comparison.sh — runs the real-simulation comparison for the given variants on
# the benchmark host, one after another, each in a throw-away container from a
# digest-pinned JRE image. Runs ON the host (copy it there with the trees).
#
# Layout under $CMP (default ~/bench/cmp):
#   trees/<variant>/        the build/install/evochora tree of one clean checkout
#   consumer-classes/       compiled tools/bench-server/consumer/*.java
#   config/perf_server.conf the comparison configuration (+ the evochora.conf it includes)
#   config/<variant>/       the same pair for one variant, when the variants need different
#                           configurations (a config key one side does not accept); a variant
#                           without its own perf_server.conf uses config/perf_server.conf
#   config/<variant>/image  the JRE image of one variant, when the JDK itself is part of the
#                           change under comparison; a variant without it uses the default image
#   logs/<variant>.log      node output of each run
#   progress.txt            one line per variant: seconds and final TICKHASH
#
# Usage: run-comparison.sh <variant>...
# Every variant waits for a quiet host (1-min load <= BENCH_MAX_LOAD) and is skipped,
# not measured, when the host is busy. Wall seconds run from "SimulationEngine started"
# to "auto-paused at tick"; identical TICKHASH lines prove identical behaviour.
set -u
CMP="${CMP:-$HOME/bench/cmp}"
IMAGE="${BENCH_IMAGE:-eclipse-temurin:25-jre-resolute@sha256:41d9263c0d5b2a74d89af6e29e73b04298bc65e3cb705d03e9e2271ef49fef84}"
MAX_LOAD="${BENCH_MAX_LOAD:-0.5}"
rm -f "$CMP/progress.txt"
for v in "$@"; do
    sleep 180
    load=$(cut -d' ' -f1 /proc/loadavg)
    awk -v l="$load" -v m="$MAX_LOAD" 'BEGIN { exit (l > m) ? 1 : 0 }' \
        || { echo "$v SKIPPED host busy load=$load" >> "$CMP/progress.txt"; continue; }
    log="$CMP/logs/$v.log"; mkdir -p "$CMP/logs"; rm -rf "$CMP/perf-data" "$log"
    conf="/cmp/config/perf_server.conf"; [ -f "$CMP/config/$v/perf_server.conf" ] && conf="/cmp/config/$v/perf_server.conf"
    image="$IMAGE"; [ -f "$CMP/config/$v/image" ] && image=$(cat "$CMP/config/$v/image")
    docker run --rm -u "$(id -u):$(id -g)" -v "$CMP:/cmp" -w "/cmp/trees/$v" --name benchcmp "$image" \
        java -Xms4g -Xmx4g -XX:+AlwaysPreTouch --enable-native-access=ALL-UNNAMED -cp "/cmp/consumer-classes:lib/*" \
        org.evochora.cli.CommandLineInterface --config "$conf" node run > "$log" 2>&1 &
    for i in $(seq 1 720); do
        sleep 10
        grep -q "auto-paused at tick" "$log" && break
        docker ps --format '{{.Names}}' | grep -q benchcmp || break
    done
    start_ts=$(grep -m1 "SimulationEngine started" "$log" | sed 's/\x1b\[[0-9;]*m//g' | awk '{print $1" "$2}')
    end_ts=$(grep -m1 "auto-paused at tick" "$log" | sed 's/\x1b\[[0-9;]*m//g' | awk '{print $1" "$2}')
    hashline=$(grep "TICKHASH" "$log" | tail -1 | sed 's/\x1b\[[0-9;]*m//g' | grep -o "TICKHASH.*")
    docker stop -t 5 benchcmp > /dev/null 2>&1
    wait
    if [ -n "$start_ts" ] && [ -n "$end_ts" ]; then
        secs=$(( $(date -d "$end_ts" +%s) - $(date -d "$start_ts" +%s) ))
        echo "$v OK seconds=$secs | $hashline" >> "$CMP/progress.txt"
    else
        echo "$v FAILED lastseen=$hashline" >> "$CMP/progress.txt"
    fi
    rm -rf "$CMP/perf-data"
done
echo CMP_ALL_DONE >> "$CMP/progress.txt"
