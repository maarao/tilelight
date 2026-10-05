#!/bin/bash
# Cost of the HDR lighting / bloom / glint pass: vanilla vs Tilelight defaults, interleaved.
H=$(cd "$(dirname "$0")" && pwd)
export TRACE=1 RESULTS=/tmp/mcbench/look.txt WAIT=45
for i in 1 2 3; do
  "$H/setmode.sh" off 1.0 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "look-off-$i"
  "$H/setmode.sh" taa 0.67 -Dtilelight.bloom=0 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "look-nobloom-$i"
  "$H/setmode.sh" taa 0.67 >/dev/null; "$H/bench.sh" "26.3 Tilelight" "look-default-$i"
done
sed -i '' 's|^JvmArgs=.*|JvmArgs=--enable-native-access=ALL-UNNAMED|' "$HOME/Library/Application Support/PrismLauncher/instances/26.3 Tilelight/instance.cfg"
echo LOOK_DONE
