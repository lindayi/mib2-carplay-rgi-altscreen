package com.luka.carplay.core;

import de.audi.app.terminalmode.IContext;
import de.audi.atip.base.IFrameworkAccess;

/* Only external services/modules are fake; tests compile the real CarPlayApp. */
abstract class LifecycleTestModule implements Module {
    private static Object stateLock() {
        try {
            java.lang.reflect.Field field = CarPlayApp.class.getDeclaredField("lock");
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    static final Object gate = new Object();
    static final int[] starts = new int[5], stops = new int[5];
    static int blockIndex = -1, entered = -1;
    static boolean released;
    static volatile boolean badLockOrder;
    final int index;
    LifecycleTestModule(int i) { index = i; }
    public String name() { return "lifecycle-test-" + index; }
    static void checkLock() {
        if (Thread.holdsLock(stateLock())) {
            badLockOrder = true;
            throw new AssertionError("external module called under state lock");
        }
    }
    public boolean start(FrameworkRef fw) {
        checkLock();
        synchronized (gate) {
            starts[index]++;
            if (blockIndex == index) {
                entered = index;
                gate.notifyAll();
                long deadline = System.currentTimeMillis() + 5000;
                while (!released) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) throw new AssertionError("module release timeout");
                    try { gate.wait(remaining); }
                    catch (InterruptedException x) { throw new AssertionError(x); }
                }
                blockIndex = -1;
            }
        }
        return true;
    }
    public void stop() { checkLock(); synchronized (gate) { stops[index]++; } }
    static int count(int index) { synchronized (gate) { return starts[index]; } }
}
final class ScreenModule extends LifecycleTestModule { ScreenModule() { super(0); } }
final class RgdModule extends LifecycleTestModule {
    static volatile int refreshes;
    RgdModule() { super(1); }
    public void refreshServiceBindings() { checkLock(); refreshes++; }
    public void retryCleanup() { checkLock(); }
}
final class SteeringWheelInputModule extends LifecycleTestModule { SteeringWheelInputModule() { super(2); } }
final class SpeedSourceDiagnostics extends LifecycleTestModule { SpeedSourceDiagnostics() { super(3); } }
final class ScreenNavStatusGate {
    static volatile int inactiveRetries;
    static void retryPending(boolean active) {
        LifecycleTestModule.checkLock();
        if (!active) inactiveRetries++;
    }
}
