#!/bin/bash
# usage: run.sh <label> <enabled> <profile> <renderScale>
set -u
LABEL=$1; EN=$2; PROF=$3; SCALE=$4; EXT=${5:-1}
I="$HOME/Library/Application Support/PrismLauncher/instances/26.3 Rockstar"
M="$I/minecraft"; L="$M/logs/latest.log"
cp /tmp/mcbench/player-start.dat "$M/saves/bench/players/data/d597980c-8763-32bc-9670-6f8121436264.dat"
/tmp/mcbench/venv/bin/python - "$M/config/rockstarshaders.json" "$EN" "$PROF" "$SCALE" "$EXT" <<'PY'
import json,sys
p,en,prof,scale,ext=sys.argv[1:]
d=json.load(open(p))
d.update(enabled=(en=="1"), profile=prof, renderScale=float(scale), targetFps=0,
         showFps=True, showPasses=True, fpsExtended=True, backendPromptDismissed=True, extendedDistance=(ext=="1"))
json.dump(d,open(p,"w"),indent=2)
PY
touch /tmp/mcbench/stamp
"/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch "26.3 Rockstar" >/tmp/mcbench/prism-$LABEL.log 2>&1 &
until [ "$L" -nt /tmp/mcbench/stamp ] && grep -q "joined the game" "$L"; do sleep 1; done
sleep 50
rm -f /tmp/mcbench/GO; echo READY > /tmp/mcbench/READY
until [ -f /tmp/mcbench/GO ]; do sleep 1; done
rm -f /tmp/mcbench/READY /tmp/mcbench/GO
cp "$L" "/tmp/mcbench/$LABEL.log"
pkill -f "Xdock:name=.*26.3 Rockstar"; pkill -f "prismlauncher --launch"
while pgrep -f "Xdock:name=.*26.3 Rockstar" >/dev/null; do sleep 1; done
sleep 2
echo "done $LABEL"
