#!/bin/bash
# Clean re-run of Phase 0 after discovering Unreal Editor/Blender contaminated the first runs.
H=$(cd "$(dirname "$0")" && pwd)
P="$HOME/Library/Application Support/PrismLauncher/instances"
T="$P/26.3 Tilelight"; TO="$T/minecraft/options.txt"
R="$P/26.3 Rockstar"; RM="$R/minecraft"
B="$P/1.21.11 Bench/minecraft"
export TRACE=1 RESULTS=/tmp/mcbench/rerun.txt WAIT=60
set_opt() { sed -i '' "s/^$1:.*/$1:$2/" "$3"; }
# vanilla 26.3: Vulkan again (repeatability), OpenGL, quarter pixels
"$H/bench.sh" "26.3 Tilelight" vk-native
set_opt preferredGraphicsBackend '"opengl"' "$TO"; "$H/bench.sh" "26.3 Tilelight" gl-native
set_opt preferredGraphicsBackend '"vulkan"' "$TO"
sed -i '' -e 's/^MinecraftWinWidth=.*/MinecraftWinWidth=864/' -e 's/^MinecraftWinHeight=.*/MinecraftWinHeight=542/' "$T/instance.cfg"
"$H/bench.sh" "26.3 Tilelight" vk-quarterpx
sed -i '' -e 's/^MinecraftWinWidth=.*/MinecraftWinWidth=1728/' -e 's/^MinecraftWinHeight=.*/MinecraftWinHeight=1084/' "$T/instance.cfg"
# 1.21.11: OpenGL vs MetalCraft
"$H/bench.sh" "1.21.11 Bench" gl121
cp /tmp/mcbench/jars/metalcraft-0.1.0.jar "$B/mods/"; "$H/bench.sh" "1.21.11 Bench" metalcraft121; rm -f "$B/mods/metalcraft-0.1.0.jar"
# Rockstar at its default 75% and at 100%
for s in 0.75 1.0; do
  /tmp/mcbench/venv/bin/python - "$RM/config/rockstarshaders.json" "$s" <<'PY'
import json,sys; p,s=sys.argv[1:]; d=json.load(open(p)); d.update(renderScale=float(s), targetFps=0, enabled=True, extendedDistance=True); json.dump(d,open(p,"w"),indent=2)
PY
  "$H/bench.sh" "26.3 Rockstar" rockstar-$s
done
echo RERUN_DONE
