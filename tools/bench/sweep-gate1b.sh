#!/bin/bash
# Gate 1 repeat runs: off and noop alternate so drift (thermals, background load) hits both equally.
H=$(cd "$(dirname "$0")" && pwd)
export TRACE=1 RESULTS=/tmp/mcbench/gate1b.txt WAIT=45
run() { "$H/setmode.sh" "$1" "$2" >/dev/null; "$H/bench.sh" "26.3 Tilelight" "g1b-$1-$2-$3"; }
for i in 1 2 3; do run off 1.0 $i; run noop 1.0 $i; run metalfx 0.67 $i; done
"$H/setmode.sh" metalfx 0.67 >/dev/null
echo GATE1B_DONE
