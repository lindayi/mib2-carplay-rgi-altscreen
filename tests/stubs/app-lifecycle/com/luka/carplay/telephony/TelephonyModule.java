package com.luka.carplay.telephony;

import com.luka.carplay.core.FrameworkRef;
import com.luka.carplay.core.Module;

public final class TelephonyModule implements Module {
    private final Module delegate = FrameworkRef.telephonyFixture();
    public String name() { return delegate.name(); }
    public boolean start(FrameworkRef framework) { return delegate.start(framework); }
    public void stop() { delegate.stop(); }
}
