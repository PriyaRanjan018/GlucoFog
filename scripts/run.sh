#!/usr/bin/env bash
# Compile GlucoFog and run one simulation. Arguments are passed through:
#   scripts/run.sh --mode edge --patients 5 --wan-ms 80
set -euo pipefail
cd "$(dirname "$0")/.."

V=vendor/iFogSim
CP="build/ifogsim:$V/jars/*:$V/jars/commons-math3-3.5/*"
if [ -z "${GLUCOFOG_NO_BUILD:-}" ]; then
  mkdir -p build/classes
  javac -nowarn -cp "$CP" -d build/classes src/main/java/glucofog/*.java
fi
exec java -cp "build/classes:$CP" glucofog.GlucoFogSimulation "$@"
