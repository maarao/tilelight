#!/bin/bash
# Gate 2: cost of sun shadows on top of the Gate 1 upscale, interleaved.
H=$(cd "$(dirname "$0")" && pwd)
export TRACE=1 RESULTS=/tmp/mcbench/gate2.txt WAIT=45
for i in 1 2 3; do
  "$H/setmode.sh" off 1.0 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g2-off-$i"
  "$H/setmode.sh" metalfx 0.67 -Dtilelight.shadows=false >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g2-metalfx-$i"
  "$H/setmode.sh" metalfx 0.67 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g2-shadows-$i"
done
echo GATE2_DONE
