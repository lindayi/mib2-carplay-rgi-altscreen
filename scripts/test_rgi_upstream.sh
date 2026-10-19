#!/bin/bash
# Exact-stock lifecycle regressions. All outputs stay isolated from release builds.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/build/rgi-upstream-tests"
TOOLS_DIR=${TOOLS_DIR:?Set TOOLS_DIR to the converter checkout}
STOCK="$TOOLS_DIR/out/${STOCK_JAR:-MU1316-P5145-stock.jar}"
CP="$STOCK:$TOOLS_DIR/libs/org.osgi.framework-1.10.0.jar:$TOOLS_DIR/libs/org.osgi.util.tracker-1.5.4.jar"
ASM="$TOOLS_DIR/tools/uninline/lib/asm-9.7.jar:$TOOLS_DIR/tools/uninline/lib/asm-tree-9.7.jar"
rm -rf "$OUT/classes" "$OUT/tools" "$OUT/tests"
mkdir -p "$OUT/classes" "$OUT/tools" "$OUT/tests"
find "$ROOT/Toolbox/carplay_alt_screen/hmi/src" -name '*.java' -type f > "$OUT/sources"
javac -source 1.4 -target 1.4 -encoding UTF-8 -Xlint:-options -cp "$CP" \
    -d "$OUT/classes" @"$OUT/sources"
cp -R "$ROOT/Toolbox/carplay_alt_screen/hmi/resources/." "$OUT/classes/"
javac -cp "$ASM" -d "$OUT/tools" "$ROOT/tools/PatchNativeRouteState.java" \
    "$ROOT/tests/NativeRouteStatePatchTest.java"
java -cp "$OUT/tools:$ASM" NativeRouteStatePatchTest "$STOCK"
java -cp "$OUT/tools:$ASM" PatchNativeRouteState "$STOCK" "$OUT/classes"
javac -encoding UTF-8 -cp "$OUT/classes:$CP" -d "$OUT/tests" \
    "$ROOT/tests/NativeRouteStateOverlayTest.java" "$ROOT/tests/RgiGateLifecycleTest.java" \
    "$ROOT/tests/RgiServiceRecoveryTest.java"
java -Xverify:all -cp "$OUT/tests:$OUT/classes:$CP" NativeRouteStateOverlayTest
# Reconstructed IBM J9 Navigation bytecode requires the existing isolated-probe policy.
java -Xverify:none -cp "$OUT/tests:$OUT/classes:$CP" com.luka.carplay.core.RgiGateLifecycleTest
java -Xverify:none -cp "$OUT/tests:$OUT/classes:$CP" com.luka.carplay.routeguidance.RgiServiceRecoveryTest
echo RGI_UPSTREAM_LIFECYCLE=PASS
