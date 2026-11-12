#!/bin/bash
# Build and run the offline landing testbed.
#
#   ./sim/run.sh          comparison report (1.1.7 heuristics vs current law)
#   ./sim/run.sh check    behavioural assertions on the landing law
#   ./sim/run.sh sweep    parameter search
#
# Needs only a JDK. The JVM flags keep the address-space footprint small so
# this also runs inside restricted sandboxes.
set -e
cd "$(dirname "$0")/.."

JAVA_BIN="${JAVA_BIN:-/home/Water500t/android/aviumui/prebuilts/jdk/jdk21/linux-x86/bin}"
if [ ! -x "$JAVA_BIN/javac" ]; then
  JAVA_BIN="$(dirname "$(readlink -f "$(command -v javac)")")"
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

JVM_FLAGS=(-Xmx96m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -XX:+UseSerialGC)

"$JAVA_BIN/javac" "${JVM_FLAGS[@]/#/-J}" -d "$OUT" \
  src/main/java/com/water500t/faautoroute/LandingLaw.java \
  sim/LandingSim.java

exec "$JAVA_BIN/java" "${JVM_FLAGS[@]}" -cp "$OUT" com.water500t.faautoroute.LandingSim "$@"
