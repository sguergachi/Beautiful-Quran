#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <Gradle JVM classpath.txt> [cached WAV folder] [results folder]" >&2
    exit 2
fi
cd "$(dirname "$0")/../.."
classpath="app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes:$(cat "$1")"
input="${2:-tools/.cache/tarji_detector_audit}"
output="${3:-tools/tarji_samples/detector-audit-results}"
mkdir -p "$output"
sha256sum app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/com/beautifulquran/playback/Tarji*.class > "$output/detector-audit-class-digests.txt"
build="$(mktemp -d /tmp/bq-detector-audit.XXXXXX)"
trap 'rm -rf "$build"' EXIT
javac -cp "$classpath" -d "$build" tools/tarji_samples/DetectorAudit.java
java -Xms256m -Xmx1g -cp "$build:$classpath" DetectorAudit "$input" "$output"
if [[ -f "$input/baseline-before.csv" ]]; then
    cmp "$input/baseline-before.csv" "$output/baseline-after.csv"
    cp "$input/baseline-before.csv" "$output/baseline-before.csv"
    echo "Baseline before/after: byte-identical."
elif [[ -f "$output/baseline-before.csv" ]]; then
    cmp "$output/baseline-before.csv" "$output/baseline-after.csv"
    echo "Baseline before/after: byte-identical."
else
    echo "No frozen baseline digest available; before/after comparison not performed." >&2
fi
