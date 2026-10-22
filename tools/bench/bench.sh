#!/bin/bash
# usage: bench.sh "<prism instance name>" <label>
#
# Compare configurations only within one interleaved sweep: absolute GPU times drifted by ~25%
# across a day of runs on the same machine (6.7 -> 8.4 ms for vanilla), cause not pinned down.
#
# Puts the camera at the fixed spot, launches into world "bench", waits $WAIT seconds
# (default 50) for chunks to load, then either:
#   TRACE=1  records a 5 s Metal System Trace and appends the parsed GPU time to $RESULTS
#   SHOT=1   saves a screenshot of the game window to $OUT/$LABEL.png
#   default  signals READY and waits for GO (manual screenshot via computer use)
# CAM (see setcam.py) picks the camera, including the dimension.
# and finally quits the game.
set -u
INST=$1; LABEL=$2
WAIT=${WAIT:-50}; TRACE=${TRACE:-0}; SHOT=${SHOT:-0}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-/tmp/mcbench}; RESULTS=${RESULTS:-$OUT/results.txt}
mkdir -p "$OUT"
M="$HOME/Library/Application Support/PrismLauncher/instances/$INST/minecraft"; L="$M/logs/latest.log"
PAT="Xdock:nam[e]=.*$INST"
# Restore a frozen copy of the world so every run sees the same generated terrain.
SNAPSHOT=${SNAPSHOT:-$HOME/Library/Caches/tilelight-bench/world-26.3}
if [ -d "$SNAPSHOT" ] && [[ "$INST" == 26.3* ]]; then rsync -a --delete "$SNAPSHOT/" "$M/saves/bench/"; fi
/tmp/mcbench/venv/bin/python "$HERE/setcam.py" "$M/saves/bench"
touch "$OUT/stamp"
"/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch "$INST" >"$OUT/prism-$LABEL.log" 2>&1 &
until [ "$L" -nt "$OUT/stamp" ] && grep -q "joined the game" "$L"; do
  grep -qE "Reported exception|Crash report saved|has crashed" "$L" 2>/dev/null && [ "$L" -nt "$OUT/stamp" ] && { echo "CRASH $LABEL"; cp "$L" "$OUT/$LABEL.log"; exit 1; }
  sleep 1
done
sleep "$WAIT"
if [ "$TRACE" = 1 ]; then
  GP=$(pgrep -f "$PAT" | head -1)
  rm -rf "$OUT/$LABEL.trace"
  xcrun xctrace record --template "Metal System Trace" --attach "$GP" --time-limit 5s --output "$OUT/$LABEL.trace" >/dev/null 2>&1
  PARSED=$(python3 "$HERE/gpuparse.py" "$OUT/$LABEL.trace")
  { echo "== $LABEL"; echo "$PARSED" | grep -A4 -E "^(window|java)"; } | tee -a "$RESULTS"
  # Other apps on the GPU (Unreal, Blender, browsers playing video) silently inflated our first
  # Phase 0 numbers ~2.5x, showing up as WindowServer 64-79% busy instead of the clean ~9%.
  # Flag any other process above 5% of the GPU, and WindowServer above 30%.
  echo "$PARSED" | awk '/busy/ && !/^ / && !/^java/ { lim = /^WindowServer/ ? 30 : 5; gsub(/[(%]/," "); for (i=1;i<=NF;i++) if ($i=="of" && $(i-1)+0 > lim) print "WARNING: GPU contention from " $0 }' | tee -a "$RESULTS"
elif [ "$SHOT" = 1 ]; then
  GP=$(pgrep -f "$PAT" | head -1)
  screencapture -x -o -l "$(swift "$HERE/winid.swift" "$GP")" "$OUT/$LABEL.png"
else
  rm -f "$OUT/GO"; echo "$LABEL" > "$OUT/READY"
  until [ -f "$OUT/GO" ]; do sleep 1; done
  rm -f "$OUT/READY" "$OUT/GO"
fi
cp "$L" "$OUT/$LABEL.log"
pkill -f "$PAT"; pkill -f "prismlauncher --launc[h]"
while pgrep -f "$PAT" >/dev/null; do sleep 1; done
sleep 2; echo "done $LABEL"
