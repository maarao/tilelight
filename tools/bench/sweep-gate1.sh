#!/bin/bash
# Gate 1 measurements on the "26.3 Tilelight" instance (vanilla Vulkan + Tilelight).
H=$(cd "$(dirname "$0")" && pwd)
export TRACE=1 RESULTS=/tmp/mcbench/gate1.txt WAIT=60
run() { "$H/setmode.sh" "$1" "$2" >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g1-$1-$2"; }
run off 1.0
run noop 1.0
run bilinear 0.67
run metalfx 0.67
run metalfx 0.5
run off 1.0
"$H/setmode.sh" metalfx 0.67 >/dev/null
echo GATE1_DONE
