package com.luka.carplay.routeguidance;
import com.luka.carplay.core.LifecycleProbe;
import com.luka.carplay.core.Module;

public final class RouteGuidance {
    public static volatile int refreshes, initFailures, stopFailures, takeoverFailures, initThrows, startThrows;
    private final Module fixture = LifecycleProbe.rgModule();
    private final boolean textOnly;
    private boolean running, taken;
    public RouteGuidance(boolean textOnly) { this.textOnly = textOnly; }
    public boolean init(Object service) {
        LifecycleProbe.checkLock();
        if (initThrows > 0) { initThrows--; throw new IllegalStateException("injected partial init"); }
        if (initFailures > 0) { initFailures--; return false; }
        return service != null;
    }
    public boolean isTextOnly() { return textOnly; }
    public boolean engageTakeover() {
        LifecycleProbe.checkLock();
        if (takeoverFailures > 0) { takeoverFailures--; return false; }
        taken = true; return true;
    }
    public void disengageTakeover() { LifecycleProbe.checkLock(); taken = false; }
    public boolean isRunning() { return running; }
    public void start() {
        if (!taken) throw new AssertionError("RG subscription preceded takeover");
        if (startThrows > 0) {
            startThrows--; running = true;
            throw new IllegalStateException("injected partial start after running publication");
        }
        fixture.start(null); running = true;
    }
    public void stop() {
        if (stopFailures > 0) { stopFailures--; throw new IllegalStateException("injected stop"); }
        fixture.stop(); running = false;
    }
    public void refreshServiceBindings() { LifecycleProbe.checkLock(); refreshes++; }
}
