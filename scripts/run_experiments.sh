#!/usr/bin/env bash
# Run the full experiment sweep. Every configuration is repeated REPS times because
# iFogSim's tuple selectivity is random. Raw console output goes to results/raw/.
#   scripts/run_experiments.sh            # REPS=10
#   REPS=3 scripts/run_experiments.sh     # quick pass
set -euo pipefail
cd "$(dirname "$0")/.."

REPS=${REPS:-10}
JOBS=${JOBS:-$(( $(nproc) / 2 > 0 ? $(nproc) / 2 : 1 ))}
OUT=results/raw
mkdir -p "$OUT" build/classes

V=vendor/iFogSim
javac -nowarn -cp "build/ifogsim:$V/jars/*:$V/jars/commons-math3-3.5/*" -d build/classes src/main/java/glucofog/*.java
export GLUCOFOG_NO_BUILD=1

# patients wan_ms interval_ms  (duplicates removed below)
configs() {
  for p in 3 5 8 12; do echo "$p 80 1000"; done          # scaling with patient count
  for w in 20 50 80 150 250; do echo "3 $w 1000"; done   # WAN latency sweep
  for i in 1000 5000 60000; do echo "3 80 $i"; done      # sensor reporting interval
}

jobs_list() {
  configs | sort -u | while read -r p w i; do
    for mode in cloud clinic edge; do
      for r in $(seq 1 "$REPS"); do
        f="$OUT/${mode}_p${p}_w${w}_i${i}_r${r}.txt"
        [ -s "$f" ] || echo "$mode $p $w $i $f"
      done
    done
  done
}

jobs_list | xargs -P "$JOBS" -L 1 bash -c \
  'scripts/run.sh --mode "$0" --patients "$1" --wan-ms "$2" --interval-ms "$3" > "$4" 2>&1 || rm -f "$4"'
echo "done: $(ls "$OUT" | wc -l) runs in $OUT"
