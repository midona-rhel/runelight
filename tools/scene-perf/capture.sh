#!/bin/sh
set -eu
if [ "$#" -ne 2 ]; then echo "Usage: $0 JAVA_PID OUTPUT_DIRECTORY" >&2; exit 1; fi
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
build=$(mktemp -d "${TMPDIR:-/tmp}/runelight-perf.XXXXXX")
trap 'rm -rf "$build"' EXIT
if [ -e "$2" ]; then echo "Use a new output directory" >&2; exit 1; fi
mkdir -p "$2"
git -C "$root/../.." rev-parse HEAD > "$2/revision.txt"
git -C "$root/../.." diff > "$2/working-tree.patch"
name=ScenePerf_$(date +%s)_$$
sed "s/ScenePerf/$name/g" "$root/ScenePerf.java" > "$build/$name.java"
javac -d "$build" "$build/$name.java"
printf 'Agent-Class: %s\n\n' "$name" > "$build/manifest"
jar cfm "$build/capture.jar" "$build/manifest" -C "$build" .
java -cp "$build" "$name" "$1" "$build/capture.jar" "$2"
# Agent writes after an 8 second sample window, then synchronous texture readback.
for i in $(seq 1 60); do
    if [ -e "$2/ERROR" ]; then cat "$2/ERROR" >&2; exit 1; fi
    if [ -e "$2/DONE" ]; then echo "Captured: $2"; exit 0; fi
    sleep 1
done
echo 'Capture did not finish within 60 seconds' >&2
exit 1
