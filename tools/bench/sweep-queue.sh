#!/bin/bash
# Shared vs separate Metal queue, interleaved with vanilla.
H=$(cd "$(dirname "$0")" && pwd)
export TRACE=1 RESULTS=/tmp/mcbench/queue.txt WAIT=45
for i in 1 2 3; do
  "$H/setmode.sh" off 1.0 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "q-off-$i"
  "$H/setmode.sh" metalfx 0.67 -Dtilelight.sameQueue=false >/dev/null; "$H/bench.sh" "26.3 Tilelight" "q-separate-$i"
  "$H/setmode.sh" metalfx 0.67 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "q-shared-$i"
done
echo QUEUE_DONE
