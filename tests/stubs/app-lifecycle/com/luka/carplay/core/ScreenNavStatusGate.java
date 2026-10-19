package com.luka.carplay.core;
public final class ScreenNavStatusGate {
    public static volatile int inactiveRetries;
    public static void retryPending(boolean active) {
        LifecycleTestModule.checkLock();
        if (!active) inactiveRetries++;
    }
}
