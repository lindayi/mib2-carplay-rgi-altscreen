package com.luka.carplay.core;

import java.lang.reflect.Field;
import de.audi.app.terminalmode.IContext;
import com.luka.carplay.CarPlayHook;
import com.luka.carplay.routeguidance.RouteGuidance;

public final class CarPlayAppLifecycleTest {
    static Object stateLock;
    private static Object value(String name) throws Exception {
        Field f = CarPlayHook.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }
    private static void set(String name, Object value) throws Exception {
        Field f = CarPlayHook.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void settled() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        do {
            synchronized (stateLock) {
                if (value("activationGeneration").equals(value("appliedGeneration"))
                        && value("serviceGeneration").equals(value("appliedServiceGeneration"))) return;
            }
            Thread.sleep(5);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("lifecycle did not settle");
    }
    private static void awaitRebind(boolean blocked) throws Exception {
        if (!blocked) { settled(); return; }
        synchronized (stateLock) {
            check(!value("serviceGeneration").equals(value("appliedServiceGeneration")),
                "Navigation edge was lost during blocked module start");
        }
    }
    private static void blockLastModule(IContext context) throws Exception {
        synchronized (LifecycleTestModule.gate) {
            LifecycleTestModule.blockIndex = 2;
            LifecycleTestModule.entered = -1;
            LifecycleTestModule.released = false;
        }
        CarPlayApp.onActivate(context);
        long deadline = System.currentTimeMillis() + 5000;
        synchronized (LifecycleTestModule.gate) {
            while (LifecycleTestModule.entered != 2) {
                long remaining = deadline - System.currentTimeMillis();
                check(remaining > 0, "lifecycle worker did not enter last module");
                LifecycleTestModule.gate.wait(remaining);
            }
        }
    }
    private static void releaseModule() {
        synchronized (LifecycleTestModule.gate) {
            LifecycleTestModule.released = true;
            LifecycleTestModule.gate.notifyAll();
        }
    }
    public static void main(String[] args) throws Exception {
        stateLock = value("LIFECYCLE_QUEUE");
        LifecycleContext fixture = new LifecycleContext();
        IContext context = fixture.context;
        String scenario = args[0];
        if (scenario.equals("publication")) {
            CarPlayApp.onActivate(context); settled();
            CarPlayApp.onNavigationServiceChanged();
            awaitRebind(false);
            check(LifecycleTestModule.count(1) == 2, "Navigation edge lost after lifecycle release");
        } else if (scenario.equals("during-start") || scenario.equals("replug")) {
            blockLastModule(context);
            CarPlayApp.onNavigationServiceChanged();
            awaitRebind(true); /* real worker waits on real lifecycle monitor */
            if (scenario.equals("replug")) {
                CarPlayApp.onDeactivate();
                CarPlayApp.onActivate(new LifecycleContext().context);
            }
            releaseModule(); settled(); awaitRebind(false);
            int expectedOther = scenario.equals("replug") ? 2 : 1;
            check(LifecycleTestModule.count(0) == expectedOther, "unexpected screen-module restart");
            check(LifecycleTestModule.count(1) == 2, "missing rebind or stale generation rebind");
            check(LifecycleTestModule.count(2) == expectedOther, "unexpected input restart");
        } else if (scenario.equals("failure")) {
            FrameworkRef.failNext = true;
            CarPlayApp.onActivate(context); settled();
            check(LifecycleTestModule.count(0) == 0, "injected constructor failure ignored");
            CarPlayApp.onDeactivateAndWait();
            CarPlayApp.onActivate(context); settled();
            check(LifecycleTestModule.count(1) == 1, "worker failed to recover after exception");
        } else if (scenario.equals("framework-retry")) {
            FrameworkRef.failNext = true;
            CarPlayApp.onActivate(context); settled();
            check(LifecycleTestModule.count(0) == 0, "injected constructor failure ignored");
            awaitCount(2, 1);
            check(CarPlayApp.isActive(), "framework retry required physical reconnect");
        } else if (scenario.equals("bounce")) {
            CarPlayApp.onActivate(context); settled();
            CarPlayApp.onDeactivate(); Thread.sleep(25); CarPlayApp.onActivate(context); settled();
            for (int i = 0; i < 3; ++i)
                check(LifecycleTestModule.count(i) == 2, "existing debounce restart behavior changed");
        } else if(scenario.equals("menu")) {
            CarPlayApp.setFeaturesEnabled(false);
            CarPlayApp.onActivate(context);settled();
            check(CarPlayApp.isSessionConnected() && !CarPlayApp.isActive(),"disabled session must stay stock");
            check(LifecycleTestModule.count(0)==0,"disabled session started cockpit module");
            CarPlayApp.setFeaturesEnabled(true);settled();
            check(CarPlayApp.isActive() && LifecycleTestModule.count(1)==1,"enabled connection not activated");
            CarPlayApp.setGuidanceEnabled(false);awaitRebind(false);
            check(LifecycleTestModule.count(1)==2 && LifecycleTestModule.count(0)==1
                && LifecycleTestModule.count(2)==1,"text-only mode must rebind RGD without restarting video/input");
            CarPlayApp.setGuidanceEnabled(true);awaitRebind(false);
            check(LifecycleTestModule.count(1)==3 && LifecycleTestModule.count(0)==1,"live guidance re-enable");
            CarPlayApp.setFeaturesEnabled(false);settled();
            check(!CarPlayApp.isActive() && CarPlayApp.isSessionConnected(),"disable lost physical session");
            CarPlayApp.onDeactivateAndWait();
            check(!CarPlayApp.isSessionConnected(),"disconnect left stale phone context");
            CarPlayApp.setFeaturesEnabled(true);settled();
            check(!CarPlayApp.isActive(),"enable resurrected a disconnected phone");
        } else if (scenario.equals("maintenance")) {
            CarPlayApp.onActivate(context); settled();
            Thread.sleep(1250);
            check(RouteGuidance.refreshes > 0, "resident worker did not refresh RGI bindings");
            for (int i = 0; i < 5; i++)
                check(LifecycleTestModule.count(i) == 1, "maintenance restarted a module");
            CarPlayApp.onDeactivateAndWait(); settled();
            int before = RouteGuidance.refreshes;
            Thread.sleep(1250);
            check(RouteGuidance.refreshes == before, "inactive maintenance refreshed RGI");
            check(ScreenNavStatusGate.inactiveRetries > 0, "inactive cleanup was not retried");
        } else if (scenario.equals("retry-worker") || scenario.equals("retry-replug")) {
            RouteGuidance.retryOnly = true;
            if (scenario.equals("retry-replug")) {
                RouteGuidance.retryEntered = new java.util.concurrent.CountDownLatch(1);
                RouteGuidance.retryRelease = new java.util.concurrent.CountDownLatch(1);
            }
            CarPlayApp.onActivate(context); settled();
            check(LifecycleTestModule.count(1) == 0, "failed initial attempt started RGI");
            if (scenario.equals("retry-replug")) {
                check(RouteGuidance.retryEntered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "upstream retry worker did not retry independently of the resident monitor");
                CarPlayApp.onDeactivate();
                check(!CarPlayApp.isSessionConnected(), "blocked retry blocked OEM disconnect callback");
                CarPlayApp.onActivate(new LifecycleContext().context);
                RouteGuidance.retryOnly = false;
                RouteGuidance.retryRelease.countDown();
                settled();
                check(fixture.acquired == fixture.released, "stale retry kept the old service binding");
            }
            awaitCount(2, 1);
            check(LifecycleTestModule.count(1) == 1, "retry subscribed a stale or duplicate RGI");
        } else if (scenario.equals("cleanup-replug")) {
            CarPlayApp.onActivate(context); settled();
            RouteGuidance.stopFailures = 4;
            CarPlayApp.onDeactivate();
            LifecycleContext next = new LifecycleContext();
            CarPlayApp.onActivate(next.context); settled();
            awaitCount(2, 2);
            check(fixture.acquired == fixture.released && next.adds == 1,
                "pending old cleanup prevented the new session from starting");
        } else if (scenario.equals("pending-service-rebind")) {
            fixture.identity = false; fixture.wrappers = true;
            CarPlayApp.onActivate(context); settled();
            synchronized (value("RGI_LOCK")) {
                fixture.releaseFailures = 100;
                set("lastServiceProbe", Long.valueOf(0));
                CarPlayHook.pollRgiRuntime();
                check(!((java.util.Vector)value("pendingReleases")).isEmpty(),
                    "probe-release failure was not injected");
                CarPlayApp.onNavigationServiceChanged();
            }
            settled();
            fixture.releaseFailures = 0;
            awaitCount(1, 2);
            check(LifecycleTestModule.count(0) == 1 && LifecycleTestModule.count(2) == 1,
                "service-only rebind restarted unrelated modules");
        } else if (scenario.equals("cleanup-context-rebind")) {
            CarPlayApp.onActivate(context); settled();
            RouteGuidance.stopFailures = 100;
            CarPlayApp.onDeactivateAndWait();
            LifecycleContext next = new LifecycleContext();
            CarPlayApp.onActivate(next.context); settled();
            synchronized (value("RGI_LOCK")) {
                RouteGuidance.stopFailures = 0;
                set("lastServiceProbe", Long.valueOf(com.luka.carplay.framework.ElapsedClock.nowMs()));
                CarPlayHook.pollRgiRuntime();
                check(LifecycleTestModule.count(1) == 1, "fixture did not pause before next init probe");
                CarPlayApp.onNavigationServiceChanged();
            }
            settled();
            awaitCount(2, 2);
            check(((FrameworkRef)value("fwRef")).context == next.context,
                "new activation reused the old FrameworkRef after deferred cleanup");
        } else if (scenario.equals("late-device")) {
            fixture.deviceAvailable = false;
            CarPlayApp.onActivate(context); settled();
            check(LifecycleTestModule.count(0) == 1 && LifecycleTestModule.count(1) == 0
                && LifecycleTestModule.count(2) == 0, "late device bypassed ordered startup");
            fixture.deviceAvailable = true;
            awaitCount(2, 1);
            check(fixture.adds == 1, "late device listener not registered exactly once");
        } else if (scenario.equals("services")) {
            fixture.nullFirst = true;
            fixture.wrappers = true;
            fixture.identity = false;
            CarPlayApp.onActivate(context); settled();
            check(LifecycleTestModule.count(1) == 1, "null first navigation reference blocked valid service");
            Thread.sleep(1250);
            check(LifecycleTestModule.count(1) == 1, "fresh wrapper restarted identical RGI");
            check(fixture.acquired - fixture.released == 1
                && fixture.rawAcquired == fixture.rawReleased, "wrapper probes not balanced");
            fixture.service = new Object();
            awaitCount(1, 2);
            fixture.serviceAvailable = false;
            Thread.sleep(1250);
            check(fixture.acquired == fixture.released, "removed service retained acquisition");
            fixture.serviceAvailable = true;
            awaitCount(1, 3);
        } else if (scenario.equals("stale-device")) {
            CarPlayApp.onActivate(context); settled();
            Object oldListener = fixture.listener;
            CarPlayApp.onDeactivateAndWait();
            LifecycleContext next = new LifecycleContext();
            CarPlayApp.onActivate(next.context); settled();
            set("carplayRunning", Boolean.TRUE);
            ((de.audi.app.terminalmode.device.IActiveDeviceStateListener)oldListener).updateActiveDeviceState(null);
            check(CarPlayHook.isCarplayRunning(), "stale device callback changed new session");
            check(fixture.removes == 1 && next.adds == 1, "device listener lifetime not paired");
        } else if (scenario.equals("takeover")) {
            RouteGuidance.takeoverFailures = 2;
            CarPlayApp.onActivate(context); settled();
            check(LifecycleTestModule.count(1) == 0 && LifecycleTestModule.count(2) == 0,
                "RG subscription/input preceded takeover");
            awaitCount(2, 1);
        } else if (scenario.equals("cleanup")) {
            CarPlayApp.onActivate(context); settled();
            RouteGuidance.stopFailures = 2;
            CarPlayApp.onDeactivateAndWait();
            Thread.sleep(650);
            check(fixture.acquired == fixture.released, "failed RGI cleanup did not retry/release binding");
        } else if (scenario.equals("release-retry")) {
            fixture.identity = false; fixture.wrappers = true;
            CarPlayApp.onActivate(context); settled();
            fixture.releaseFailures = 2;
            Thread.sleep(1650);
            check(fixture.acquired - fixture.released == 1, "failed wrapper release was lost");
            fixture.releaseFailures = 2; fixture.removeFailures = 2;
            CarPlayApp.onDeactivateAndWait();
            Thread.sleep(650);
            check(fixture.acquired == fixture.released && fixture.removes == 1,
                "inactive service/listener cleanup was not retried");
        } else if (scenario.equals("partial-start")) {
            RouteGuidance.initFailures = 2;
            CarPlayApp.onActivate(context); settled();
            check(LifecycleTestModule.count(1) == 0 && LifecycleTestModule.count(2) == 0,
                "failed RGI init bypassed startup dependency");
            awaitCount(2, 1);
            check(LifecycleTestModule.stops[1] >= 2, "partial RGI initialization was not retired");
        } else if (scenario.equals("partial-throw")) {
            RouteGuidance.initThrows = 1; RouteGuidance.startThrows = 1;
            CarPlayApp.onActivate(context); settled();
            check(LifecycleTestModule.count(2) == 0, "throwing initialization started input");
            awaitCount(2, 1);
            check(LifecycleTestModule.count(1) == 1 && LifecycleTestModule.stops[1] >= 2,
                "throwing partial RGI start was reused without complete initialization/subscription");
        } else if (scenario.equals("stale-service") || scenario.equals("service-edge")) {
            fixture.acquisitionEntered = new java.util.concurrent.CountDownLatch(1);
            fixture.acquisitionRelease = new java.util.concurrent.CountDownLatch(1);
            CarPlayApp.onActivate(context);
            check(fixture.acquisitionEntered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "actor never reached service acquisition");
            if (scenario.equals("stale-service")) {
                CarPlayApp.onDeactivate();
                check(!CarPlayApp.isActive() && !CarPlayApp.isSessionConnected(),
                    "blocked service acquisition blocked immediate disconnect publication");
                CarPlayApp.onActivate(new LifecycleContext().context);
            } else CarPlayApp.onNavigationServiceChanged();
            fixture.acquisitionRelease.countDown();
            settled();
            check(LifecycleTestModule.count(1) == 1 && LifecycleTestModule.count(2) == 1,
                "stale service initialization subscribed or started dependent input");
            check(fixture.acquired == fixture.released + (scenario.equals("service-edge") ? 1 : 0),
                "stale navigation acquisition not balanced");
        } else if (scenario.equals("g24")) {
            LifecycleProbe.unsupported = true;
            CarPlayApp.onActivate(context); settled();
            check(LifecycleTestModule.count(1) == 0 && fixture.acquired == 0, "G24 acquired RGI");
        } else throw new AssertionError("unknown scenario");
        CarPlayApp.onDeactivateAndWait(); settled();
        check(!CarPlayApp.isActive(), "disconnect remained active");
        check(!LifecycleTestModule.badLockOrder, "module ran under small state lock");
        System.out.println("CarPlayAppLifecycleTest: " + scenario + " PASS");
    }
    private static void awaitCount(int index, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (LifecycleTestModule.count(index) != count) {
            check(System.currentTimeMillis() < deadline, "module " + index + " did not reach " + count);
            Thread.sleep(5);
        }
    }
}
