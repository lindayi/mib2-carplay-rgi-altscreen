package com.luka.carplay.core;

import de.audi.app.terminalmode.IContext;
import de.audi.atip.base.IFrameworkAccess;

public final class FrameworkRef {
    public static Module telephonyFixture() { return new LifecycleTestModule(4) {}; }
    static volatile boolean failNext;
    FrameworkRef(IContext context) {
        if (failNext) { failNext = false; throw new IllegalStateException("injected framework failure"); }
    }
    boolean isReady() { return true; }
    IFrameworkAccess framework() { return null; }
}
