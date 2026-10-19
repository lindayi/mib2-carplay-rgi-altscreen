package com.luka.carplay.core;

import de.audi.app.terminalmode.IContext;
import de.audi.atip.base.IFrameworkAccess;

public final class FrameworkRef {
    public static Module telephonyFixture() { return new LifecycleTestModule(4) {}; }
    static volatile boolean failNext;
    public FrameworkRef(IContext context) {
        LifecycleProbe.checkLock();
        if (failNext) { failNext = false; throw new IllegalStateException("injected framework failure"); }
    }
    public boolean isReady() { return true; }
    public IFrameworkAccess framework() { return null; }
}
