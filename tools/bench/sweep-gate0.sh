#!/bin/bash
# Settles "geometry vs pixels": vary render distance at native res, plus fullscreen.
# Runs vanilla Vulkan in the "26.3 Tilelight" instance with the Tilelight jar moved aside.
H=$(cd "$(dirname "$0")" && pwd)
I="$HOME/Library/Application Support/PrismLauncher/instances/26.3 Tilelight"; O="$I/minecraft/options.txt"
mkdir -p /tmp/mcbench/aside; mv "$I"/minecraft/mods/tilelight-*.jar /tmp/mcbench/aside/ 2>/dev/null
export TRACE=1 RESULTS=/tmp/mcbench/gate0.txt
set_opt() { sed -i '' "s/^$1:.*/$1:$2/" "$O"; }
set_opt preferredGraphicsBackend '"vulkan"'
for rd in 6 10 16 24; do set_opt renderDistance $rd; WAIT=75 "$H/bench.sh" "26.3 Tilelight" rd$rd; done
set_opt renderDistance 16; set_opt fullscreen true
WAIT=75 "$H/bench.sh" "26.3 Tilelight" rd16-fullscreen
set_opt fullscreen false
mv /tmp/mcbench/aside/tilelight-*.jar "$I/minecraft/mods/"
echo GATE0_DONE
