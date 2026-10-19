#!/bin/bash
# Shared isolated lifecycle source list. Caller supplies PROJECT_DIR and stock/OSGi classpath.
LIFECYCLE_SOURCES=(
  "$PROJECT_DIR/Toolbox/carplay_alt_screen/hmi/src/com/luka/carplay/CarPlayHook.java"
  "$PROJECT_DIR/Toolbox/carplay_alt_screen/hmi/src/com/luka/carplay/DeviceStateHandler.java"
  "$PROJECT_DIR/Toolbox/carplay_alt_screen/hmi/src/com/luka/carplay/core/CarPlayApp.java"
  "$PROJECT_DIR/Toolbox/carplay_alt_screen/hmi/src/com/luka/carplay/core/Module.java"
  "$PROJECT_DIR/Toolbox/carplay_alt_screen/hmi/src/com/luka/carplay/core/LifecycleFeatures.java"
  "$PROJECT_DIR/Toolbox/carplay_alt_screen/hmi/src/com/luka/carplay/framework/ElapsedClock.java"
  "$PROJECT_DIR/tests/CarPlayAppLifecycleTest.java"
  "$PROJECT_DIR/tests/LifecycleContext.java"
  "$PROJECT_DIR/tests/stubs/app-lifecycle/com/luka/carplay/core/LifecycleFixtures.java"
  "$PROJECT_DIR/tests/stubs/app-lifecycle/com/luka/carplay/core/LifecycleProbe.java"
  "$PROJECT_DIR/tests/stubs/app-lifecycle/com/luka/carplay/core/ScreenNavStatusGate.java"
  "$PROJECT_DIR/tests/stubs/app-lifecycle/com/luka/carplay/core/FrameworkRef.java"
  "$PROJECT_DIR/tests/stubs/app-lifecycle/com/luka/carplay/routeguidance/RouteGuidance.java"
  "$PROJECT_DIR/tests/stubs/app-lifecycle/com/luka/carplay/routeguidance/LowerBarKomo.java"
  "$PROJECT_DIR/tests/stubs/app-lifecycle/com/luka/carplay/telephony/TelephonyModule.java"
  "$PROJECT_DIR/tests/stubs/app-lifecycle/com/luka/carplay/bus/CarplayBus.java"
  "$PROJECT_DIR/tests/stubs/app-lifecycle/com/luka/carplay/framework/Log.java"
)
LIFECYCLE_SCENARIOS="publication during-start replug failure framework-retry bounce menu maintenance late-device services stale-device takeover cleanup g24 release-retry partial-start partial-throw stale-service service-edge"
