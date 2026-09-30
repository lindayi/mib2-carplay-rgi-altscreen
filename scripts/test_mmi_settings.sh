#!/bin/bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS_DIR="${TOOLS_DIR:?set TOOLS_DIR to the jxe2jar directory}"
STOCK_JAR_NAME="${STOCK_JAR:-MU1316-P5145-stock.jar}"
docker run --rm -v "$PROJECT_DIR":/src -v "$TOOLS_DIR":/tools:ro \
    -e STOCK_JAR_NAME="$STOCK_JAR_NAME" eclipse-temurin:8-jdk-jammy bash -c '
set -e
apt-get update -qq >/dev/null
apt-get install -y -qq mksh python3 build-essential >/dev/null
ln -sf /bin/mksh /bin/sh
mkdir -p /tmp/compiler
cat > /tmp/compiler/cc <<\CC
#!/bin/sh
exec /usr/bin/cc -D_GNU_SOURCE "$@"
CC
chmod 755 /tmp/compiler/cc
export PATH=/tmp/compiler:$PATH
CP=/src/build/carplay_hook.jar:/tools/out/$STOCK_JAR_NAME:/tools/libs/org.osgi.framework-1.10.0.jar:/tools/libs/org.osgi.util.tracker-1.5.4.jar
ASM=/tools/tools/uninline/lib/asm-9.7.jar:/tools/tools/uninline/lib/asm-tree-9.7.jar
mkdir -p /src/build/mmi-tests /ramdisk
gcc -shared -fPIC /src/tests/qnx_tmp_contract.c -ldl -o /tmp/qnx-tmp-contract.so
javac -cp "$CP:$ASM" -d /tmp /src/tests/PreferencesTest.java /src/tests/NativeMenuVerificationTest.java \
    /src/tools/PatchNavigationSettings.java /src/tests/PatchNavigationSettingsTest.java \
    /src/tests/JavaStockLinkageAudit.java /src/tests/TouchpadControllerTest.java /src/tests/SettingsRuntimeTest.java \
    /src/tests/MascotControlTest.java /src/tests/NativeMenuThreadTest.java /src/tests/JavaLogRotationTest.java \
    /src/tests/SteeringWheelTraceTest.java /src/tests/NativeMenuPresentationTest.java /src/tests/VcPanelTest.java \
    /src/tests/VcPanelWorkerTest.java /src/tests/MapCardsTest.java /src/tests/MapCardControlTest.java
LD_PRELOAD=/tmp/qnx-tmp-contract.so java -cp /tmp:$CP JavaLogRotationTest
java -cp /tmp:$CP com.luka.carplay.settings.PreferencesTest /src/build/mmi-tests/preferences /src/tests/fixtures/carplay-preferences.txt
java -cp /tmp:$ASM PatchNavigationSettingsTest /tools/out/$STOCK_JAR_NAME
java -Xverify:all -cp /tmp:$CP NativeMenuVerificationTest
java -Xverify:all -cp /tmp:$CP com.luka.carplay.settings.NativeMenuPresentationTest /src/tests/fixtures/carplay-preferences.txt
java -Xverify:all -cp /tmp:$CP:$ASM NativeMenuThreadTest
java -Xmx1g -cp /tmp:$ASM JavaStockLinkageAudit /src/build/carplay_hook.jar /tools/out/$STOCK_JAR_NAME \
    /tools/libs/org.osgi.framework-1.10.0.jar /tools/libs/org.osgi.util.tracker-1.5.4.jar
java -cp /tmp:$CP TouchpadControllerTest
java -cp /tmp:$CP SteeringWheelTraceTest
java -cp /tmp:$CP com.luka.carplay.settings.VcPanelTest /src/build/mmi-tests/vc-panel-control.txt
cc -std=gnu99 -Wall -Wextra -Werror /src/tests/vc_panel_protocol_test.c /src/vc_menu/protocol.c -o /tmp/vc-panel-protocol
/tmp/vc-panel-protocol /src/build/mmi-tests/vc-panel-control.txt
java -cp /tmp:$CP com.luka.carplay.settings.VcPanelWorkerTest
LD_PRELOAD=/tmp/qnx-tmp-contract.so java -cp /tmp:$CP com.luka.carplay.settings.MascotControlTest
java -cp /tmp:$CP com.luka.carplay.rgd.MapCardsTest
LD_PRELOAD=/tmp/qnx-tmp-contract.so java -cp /tmp:$CP com.luka.carplay.settings.MapCardControlTest /src/build/mmi-tests/map-cards-control.txt
java -cp /tmp:$CP com.luka.carplay.settings.SettingsRuntimeTest
bash /src/tests/settings_runtime_test.sh /src
bash /src/tests/volatile_state_test.sh /src
bash /src/scripts/test_supervisor_lifecycle.sh
mkdir -p /tmp/lifecycle
javac -encoding UTF-8 -d /tmp/lifecycle \
    /src/java_patch/com/luka/carplay/core/CarPlayApp.java /src/java_patch/com/luka/carplay/core/Module.java \
    /src/tests/CarPlayAppLifecycleTest.java \
    /src/tests/stubs/app-lifecycle/com/luka/carplay/core/LifecycleFixtures.java \
    /src/tests/stubs/app-lifecycle/com/luka/carplay/bus/CarplayBus.java \
    /src/tests/stubs/app-lifecycle/com/luka/carplay/framework/Log.java \
    /src/tests/stubs/app-lifecycle/com/luka/carplay/pdc/PdcSmallStageGuard.java \
    /src/tests/stubs/app-lifecycle/de/audi/app/terminalmode/IContext.java \
    /src/tests/stubs/app-lifecycle/de/audi/atip/base/IFrameworkAccess.java
for scenario in publication during-start replug failure bounce menu; do
    java -cp /tmp/lifecycle com.luka.carplay.core.CarPlayAppLifecycleTest "$scenario"
done
export TOOLS_DIR=/tools STOCK_JAR="$STOCK_JAR_NAME" SKIP_BUILD=1
bash /src/scripts/test_route_info.sh
bash /src/scripts/test_java_transports.sh
'
