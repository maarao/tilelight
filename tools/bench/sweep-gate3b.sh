#!/bin/bash
# Gate 3 re-measure after back-face culling: vanilla vs Metal terrain, interleaved.
H=$(cd "$(dirname "$0")" && pwd)
export TRACE=1 RESULTS=/tmp/mcbench/gate3b.txt WAIT=45
for i in 0 1 2 3; do
  "$H/setmode.sh" off 1.0 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g3b-off-$i"
  "$H/setmode.sh" metalfx 0.67 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g3b-terrain-$i"
done
echo GATE3B_DONE
