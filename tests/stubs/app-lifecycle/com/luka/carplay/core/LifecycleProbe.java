package com.luka.carplay.core;

public final class LifecycleProbe {
    public static volatile boolean unsupported;
    public static Module rgModule() { return new LifecycleTestModule(1) {}; }
    public static void checkLock() { LifecycleTestModule.checkLock(); }
}
