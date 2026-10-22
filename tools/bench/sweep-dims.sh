#!/bin/bash
# Screenshots of the Nether (lava sea) and the End (main island): vanilla terrain vs Metal terrain.
HERE=$(cd "$(dirname "$0")" && pwd)
# TRACE=1 records GPU time instead of screenshots.
[ "${TRACE:-0}" = 1 ] || export SHOT=1
export WAIT=${WAIT:-45}
SCENES=("nether|minecraft:the_nether 185.5 38 -120.5 20 12" "end|minecraft:the_end 0.5 95 90.5 180 22")
CONFIGS=("vanilla|-Dtilelight.terrain=false" "tilelight|")
for scene in "${SCENES[@]}"; do
  for cfg in "${CONFIGS[@]}"; do
    "$HERE/setmode.sh" taa 0.67 "${cfg#*|}" >/dev/null
    CAM="${scene#*|}" "$HERE/bench.sh" "26.3 Tilelight" "${scene%%|*}-${cfg%%|*}"
  done
done
"$HERE/setmode.sh" taa 0.67 >/dev/null
