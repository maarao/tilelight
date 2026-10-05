#!/bin/bash
# Gate 4 (volumetrics) cost: vanilla vs Gate 3 vs Gate 3 + volumetrics, interleaved.
H=$(cd "$(dirname "$0")" && pwd)
export TRACE=1 RESULTS=/tmp/mcbench/gate4.txt WAIT=45
for i in 0 1 2 3; do
  "$H/setmode.sh" off 1.0 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g4-off-$i"
  "$H/setmode.sh" metalfx 0.67 -Dtilelight.volumetric=false >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g4-g3-$i"
  "$H/setmode.sh" metalfx 0.67 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g4-vol-$i"
done
echo GATE4_DONE
