#!/bin/bash
# Exact MU1316 telephony compilation, linkage and host lifecycle regressions.
set -euo pipefail
ulimit -c 0
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/build/telephony-tests"
MODE="${TELEPHONY_TEST_MODE:-all}"
case "$MODE" in all|java|native) ;; *) echo "Invalid TELEPHONY_TEST_MODE" >&2; exit 2;; esac
mkdir -p "$OUT"
if [ "$MODE" != native ]; then
TOOLS_DIR="${TOOLS_DIR:?Set TOOLS_DIR to the private jxe2jar directory}"
STOCK="$TOOLS_DIR/out/${STOCK_JAR:-MU1316-P5145-stock.jar}"
CP="$STOCK:$TOOLS_DIR/libs/org.osgi.framework-1.10.0.jar:$TOOLS_DIR/libs/org.osgi.util.tracker-1.5.4.jar"
ASM="$TOOLS_DIR/tools/uninline/lib/asm-9.7.jar:$TOOLS_DIR/tools/uninline/lib/asm-tree-9.7.jar"
rm -rf "$OUT/classes" "$OUT/tests" "$OUT/audit"
mkdir -p "$OUT/classes" "$OUT/tests" "$OUT/audit"
find "$ROOT/Toolbox/carplay_alt_screen/hmi/src" -name '*.java' -print > "$OUT/sources.list"
javac -encoding UTF-8 -source 1.4 -target 1.4 -Xlint:-options \
    -cp "$CP" -d "$OUT/classes" @"$OUT/sources.list"
javac -cp "$ASM" -d "$OUT/audit" "$ROOT/tools/PatchPhoneKeyController.java" "$ROOT/tests/PatchPhoneKeyControllerTest.java"
java -cp "$OUT/audit:$ASM" PatchPhoneKeyControllerTest "$STOCK"
java -cp "$OUT/audit:$ASM" PatchPhoneKeyController "$STOCK" "$OUT/classes"
(cd "$OUT/classes" && jar cf "$OUT/telephony.jar" com/luka/carplay/telephony \
    de/audi/app/terminalmode/dsi/DSISmartphoneManagerProxy.class \
    de/audi/app/combi/bap/app/phone/ServiceManagerPhone.class \
    de/audi/tghu/phone/sm/Activator.class de/audi/tghu/system/sm/Activator.class)
(cd "$OUT/classes" && jar cf "$OUT/dependencies.jar" .)
javac -cp "$ASM" -d "$OUT/audit" "$ROOT/tests/JavaStockLinkageAudit.java"
java -cp "$OUT/audit:$ASM" JavaStockLinkageAudit "$OUT/telephony.jar" "$STOCK" \
    "$OUT/dependencies.jar" "$TOOLS_DIR/libs/org.osgi.framework-1.10.0.jar" \
    "$TOOLS_DIR/libs/org.osgi.util.tracker-1.5.4.jar"
javac -encoding UTF-8 -cp "$OUT/classes:$CP" -d "$OUT/tests" \
    "$ROOT/tests/telephony/TelephonyTest.java" "$ROOT/tests/telephony/Log.java" \
    "$ROOT/tests/telephony/PhoneControllerFixture.java"
java -Xverify:all -cp "$OUT/tests:$OUT/classes:$CP" com.luka.carplay.telephony.TelephonyTest
fi
if [ "$MODE" != java ]; then
if command -v cc >/dev/null 2>&1; then
    cc -std=c99 -D_GNU_SOURCE -Wall -Wextra -Werror -pthread \
        -I"$ROOT/Toolbox/carplay_alt_screen/rgi_native/src" "$ROOT/tests/telephony/tel_test.c" \
        "$ROOT/Toolbox/carplay_alt_screen/rgi_native/src/framework/iap2_protocol.c" -o "$OUT/tel_test"
    "$OUT/tel_test"
else
    echo 'No host C compiler; run native telephony test in host C test image.' >&2
    exit 1
fi
fi
