#!/bin/bash
# Gate 5: MetalFX temporal vs spatial upscaling cost, interleaved.
H=$(cd "$(dirname "$0")" && pwd)
export TRACE=1 RESULTS=/tmp/mcbench/gate5.txt WAIT=40
for i in 1 2; do
  "$H/setmode.sh" metalfx 0.67 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g5-spatial67-$i"
  "$H/setmode.sh" temporal 0.67 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g5-temporal67-$i"
  "$H/setmode.sh" temporal 0.5 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g5-temporal50-$i"
done
"$H/setmode.sh" metalfx 0.67 >/dev/null
echo GATE5_DONE
