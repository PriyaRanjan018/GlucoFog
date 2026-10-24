#!/usr/bin/env bash
# Fetch iFogSim at a pinned commit and compile it into build/ifogsim.
set -euo pipefail
cd "$(dirname "$0")/.."

IFOGSIM_COMMIT=5f68d3947e450d8d2b4af42670be819206be68c9

if [ ! -d vendor/iFogSim/.git ]; then
  git clone --quiet https://github.com/Cloudslab/iFogSim.git vendor/iFogSim
fi
git -C vendor/iFogSim checkout --quiet "$IFOGSIM_COMMIT"

rm -rf build/ifogsim && mkdir -p build/ifogsim
V=vendor/iFogSim
javac -nowarn -cp "$V/jars/*:$V/jars/commons-math3-3.5/*" -d build/ifogsim \
  $(find "$V/src" -name '*.java') 2>&1 | grep -v '^Note:' || true
echo "iFogSim $IFOGSIM_COMMIT built -> build/ifogsim"
