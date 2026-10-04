#!/bin/bash
# usage: bench.sh "<prism instance name>" <label>
# Puts the camera at the fixed spot, launches into world "bench", waits 50 s,
# signals READY, waits for GO (screenshot taken via computer-use), then quits.
set -u
INST=$1; LABEL=$2
M="$HOME/Library/Application Support/PrismLauncher/instances/$INST/minecraft"; L="$M/logs/latest.log"
PAT="Xdock:nam[e]=.*$INST"
/tmp/mcbench/venv/bin/python /tmp/mcbench/setcam.py "$M/saves/bench"
touch /tmp/mcbench/stamp
"/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch "$INST" >"/tmp/mcbench/prism-$LABEL.log" 2>&1 &
until [ "$L" -nt /tmp/mcbench/stamp ] && grep -q "joined the game" "$L"; do
  grep -qE "Reported exception|Crash report saved|has crashed" "$L" 2>/dev/null && [ "$L" -nt /tmp/mcbench/stamp ] && { echo "CRASH $LABEL"; cp "$L" "/tmp/mcbench/$LABEL.log"; exit 1; }
  sleep 1
done
sleep 50
rm -f /tmp/mcbench/GO; echo "$LABEL" > /tmp/mcbench/READY
until [ -f /tmp/mcbench/GO ]; do sleep 1; done
rm -f /tmp/mcbench/READY /tmp/mcbench/GO
cp "$L" "/tmp/mcbench/$LABEL.log"
pkill -f "$PAT"; pkill -f "prismlauncher --launch"
while pgrep -f "$PAT" >/dev/null; do sleep 1; done
sleep 2; echo "done $LABEL"
