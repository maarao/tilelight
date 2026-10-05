#!/bin/bash
# usage: setmode.sh <mode> <scale>   -- sets Tilelight system properties on the "26.3 Tilelight" instance
I="$HOME/Library/Application Support/PrismLauncher/instances/26.3 Tilelight/instance.cfg"
sed -i '' "s/^JvmArgs=.*/JvmArgs=\"--enable-native-access=ALL-UNNAMED -Dtilelight.mode=$1 -Dtilelight.scale=$2\"/" "$I"
grep '^JvmArgs' "$I"
