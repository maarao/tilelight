#!/bin/bash
# usage: setmode.sh <mode> <scale> [extra JVM args...]  -- Tilelight system properties for "26.3 Tilelight"
I="$HOME/Library/Application Support/PrismLauncher/instances/26.3 Tilelight/instance.cfg"
MODE=$1; SCALE=$2; shift 2
sed -i '' "s|^JvmArgs=.*|JvmArgs=\"--enable-native-access=ALL-UNNAMED -Dtilelight.mode=$MODE -Dtilelight.scale=$SCALE $*\"|" "$I"
grep '^JvmArgs' "$I"
