#!/bin/bash
# Render-thread timing split (recording / queue submit / GPU wait), vanilla vs Tilelight, interleaved.
H=$(cd "$(dirname "$0")" && pwd)
export WAIT=40
for i in 1 2; do
  for cfg in "off 1.0" "metalfx 0.67"; do
    set -- $cfg
    "$H/setmode.sh" "$1" "$2" >/dev/null
    rm -f /tmp/mcbench/READY /tmp/mcbench/GO
    ( until [ -f /tmp/mcbench/READY ]; do sleep 1; done; touch /tmp/mcbench/GO ) &
    "$H/bench.sh" "26.3 Tilelight" "cpu-$1-$i" >/dev/null 2>&1
    echo "== $1 $i"; grep -E "render thread us|cpu us/frame" "/tmp/mcbench/cpu-$1-$i.log" | tail -4 | cut -c35-200
  done
done
"$H/setmode.sh" metalfx 0.67 >/dev/null
echo CPU_DONE
