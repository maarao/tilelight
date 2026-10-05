#!/bin/bash
# Gate 3: Metal terrain vs Gate 2 (vanilla terrain + shadow overlay) vs vanilla, interleaved.
H=$(cd "$(dirname "$0")" && pwd)
export TRACE=1 RESULTS=/tmp/mcbench/gate3.txt WAIT=45
for i in 0 1 2 3; do
  "$H/setmode.sh" off 1.0 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g3-off-$i"
  "$H/setmode.sh" metalfx 0.67 -Dtilelight.terrain=false >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g3-gate2-$i"
  "$H/setmode.sh" metalfx 0.67 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g3-terrain-$i"
done
echo GATE3_DONE
