#!/bin/bash
# Compile shipping Java 1.4 sources, then exercise input and local transports.
set -euo pipefail
PROJECT_DIR=$(cd "$(dirname "$0")/.." && pwd)
TOOLS_DIR="${TOOLS_DIR:-$PROJECT_DIR/../../Tools/jxe2jar}"
TEST_JDK="${TEST_JDK:-${JAVA_HOME:-$TOOLS_DIR/jvms/zulu8.78.0.19-ca-jdk8.0.412-macosx_aarch64/zulu-8.jdk/Contents/Home}}"
[ "${SKIP_BUILD:-0}" = 1 ] || bash "$PROJECT_DIR/scripts/build_java.sh"
TEST_DIR=$(mktemp -d)
trap 'rm -rf "$TEST_DIR"' EXIT
"$TEST_JDK/bin/javac" -encoding UTF-8 -cp "$PROJECT_DIR/build/carplay_hook.jar" -d "$TEST_DIR" \
    "$PROJECT_DIR/tests/CarplayBusTransportTest.java" \
    "$PROJECT_DIR/tests/RendererServerTransportTest.java" \
    "$PROJECT_DIR/tests/TouchpadControllerTest.java" \
    "$PROJECT_DIR/tests/AltScreenLayoutTest.java" \
    "$PROJECT_DIR/tests/AltScreenLayoutLifecycleTest.java"
"$TEST_JDK/bin/java" -cp "$TEST_DIR:$PROJECT_DIR/build/carplay_hook.jar" \
    com.luka.carplay.cluster.AltScreenLayoutTest "$PROJECT_DIR/altscreen/Toolbox/scripts/cluster_layout.sh"
"$TEST_JDK/bin/java" -cp "$TEST_DIR:$PROJECT_DIR/build/carplay_hook.jar:$TOOLS_DIR/out/${STOCK_JAR:-MU1316-P5145-stock.jar}:$TOOLS_DIR/libs/org.osgi.framework-1.10.0.jar:$TOOLS_DIR/libs/org.osgi.util.tracker-1.5.4.jar" \
    com.luka.carplay.cluster.AltScreenLayoutLifecycleTest
"$TEST_JDK/bin/java" -cp "$TEST_DIR:$PROJECT_DIR/build/carplay_hook.jar" TouchpadControllerTest
"$TEST_JDK/bin/java" -cp "$TEST_DIR:$PROJECT_DIR/build/carplay_hook.jar" com.luka.carplay.bus.CarplayBusTransportTest
"$TEST_JDK/bin/java" -cp "$TEST_DIR:$PROJECT_DIR/build/carplay_hook.jar" com.luka.carplay.routeguidance.RendererServerTransportTest

# NAVSD INITIALIZING/READY must pass straight through the RGI gate
# (the altscreen INITIALIZING takeover and AltScreenStartupTest do not apply here).
STOCK_FINAL="$TOOLS_DIR/out/${STOCK_JAR:-MU1316-P5145-stock.jar}"
mkdir -p "$TEST_DIR/nav-init"
"$TEST_JDK/bin/javac" -encoding UTF-8 \
    -cp "$PROJECT_DIR/build/carplay_hook.jar:$STOCK_FINAL" \
    -d "$TEST_DIR/nav-init" \
    "$PROJECT_DIR/tests/GatedCombiServiceInitStateTest.java"
"$TEST_JDK/bin/java" \
    -cp "$TEST_DIR/nav-init:$PROJECT_DIR/build/carplay_hook.jar:$STOCK_FINAL" \
    com.luka.carplay.routeguidance.GatedCombiServiceInitStateTest
"$TEST_JDK/bin/javac" -encoding UTF-8 \
    -cp "$PROJECT_DIR/build/carplay_hook.jar:$STOCK_FINAL" \
    -d "$TEST_DIR/nav-init" "$PROJECT_DIR/tests/RouteGuidancePartialLifecycleTest.java"
"$TEST_JDK/bin/java" -Xverify:all \
    -cp "$TEST_DIR/nav-init:$PROJECT_DIR/build/carplay_hook.jar:$STOCK_FINAL:$TOOLS_DIR/libs/org.osgi.framework-1.10.0.jar:$TOOLS_DIR/libs/org.osgi.util.tracker-1.5.4.jar" \
    RouteGuidancePartialLifecycleTest

# Exercise the actual lifecycle worker against controllable external modules.
LIFECYCLE_TEST_OUT="$TEST_DIR/lifecycle" TEST_JDK="$TEST_JDK" TOOLS_DIR="$TOOLS_DIR" \
    bash "$PROJECT_DIR/scripts/test_carplay_lifecycle.sh"
# Parking resource policy also uses the freshly built shipping JAR.
bash "$PROJECT_DIR/scripts/test_pdc.sh"
