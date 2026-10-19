#!/bin/bash
# Actual upstream actor, exact-stock OEM interfaces, controllable feature endpoints.
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
TOOLS_DIR=${TOOLS_DIR:?Set TOOLS_DIR to the stock/OSGi tool directory}
JDK=${TEST_JDK:-${JAVA_HOME:?Set JAVA_HOME or TEST_JDK}}
CP="$TOOLS_DIR/out/${STOCK_JAR:-MU1316-P5145-stock.jar}:$TOOLS_DIR/libs/org.osgi.framework-1.10.0.jar:$TOOLS_DIR/libs/org.osgi.util.tracker-1.5.4.jar"
OUT=${LIFECYCLE_TEST_OUT:-$PROJECT_DIR/build/lifecycle-tests}
mkdir -p "$OUT"
source "$PROJECT_DIR/scripts/lifecycle_test_sources.sh"
"$JDK/bin/javac" -encoding UTF-8 -cp "$CP" -d "$OUT" "${LIFECYCLE_SOURCES[@]}" \
    "$PROJECT_DIR/tests/stubs/app-lifecycle/com/luka/carplay/pdc/PdcSmallStageGuard.java"
for scenario in $LIFECYCLE_SCENARIOS; do
    "$JDK/bin/java" -Xverify:all -cp "$OUT:$CP" com.luka.carplay.core.CarPlayAppLifecycleTest "$scenario"
done
