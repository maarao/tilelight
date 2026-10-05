#!/bin/bash
# Anti-aliasing options: MetalFX spatial vs our TAA + spatial vs MetalFX temporal, interleaved.
H=$(cd "$(dirname "$0")" && pwd)
export TRACE=1 RESULTS=/tmp/mcbench/aa.txt WAIT=45
for i in 1 2 3; do
  for m in metalfx taa temporal; do
    "$H/setmode.sh" "$m" 0.67 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "aa-$m-$i"
  done
done
"$H/setmode.sh" metalfx 0.67 >/dev/null
echo AA_DONE
