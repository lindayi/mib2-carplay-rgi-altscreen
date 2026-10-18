package com.luka.carplay.rgd;

import com.luka.carplay.framework.Log;
import de.audi.tghu.navi.app.Navigation;
import de.audi.tghu.navi.app.cluster.ClusterService;
import de.audi.tghu.navi.app.cluster.KOMOService;
import de.audi.tghu.navi.app.command.DSIResponseContainer;
import java.lang.reflect.*;
import sun.misc.Unsafe;

public final class RgiServiceRecoveryTest {
    static final Unsafe U;
    static {
        try { Field f = Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); U = (Unsafe)f.get(null); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    static Field field(Class c, String name) throws Exception {
        for (; c != null; c = c.getSuperclass()) try {
            Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
        } catch (NoSuchFieldException e) { }
        throw new NoSuchFieldException(name);
    }
    static Object get(Object o, String n) throws Exception { return field(o.getClass(), n).get(o); }
    static void set(Object o, String n, Object v) throws Exception { field(o.getClass(), n).set(o, v); }
    public static final class Cluster extends ClusterService {
        DSIResponseContainer container; KOMOService komo; int refreshes;
        Cluster() { super(null, null, null, null, null, null); }
        public DSIResponseContainer getDSIResponseContainer() { return container; }
        public KOMOService getKomoService() { return komo; }
        public void updateRGIString(short[] value) { }
        public void triggerRefreshRGIValid() { refreshes++; }
    }
    static Cluster cluster() throws Exception {
        Cluster c = (Cluster)U.allocateInstance(Cluster.class);
        c.container = new DSIResponseContainer(); return c;
    }
    static class Navi extends Navigation {
        Navi() { super(null); }
        void install(ClusterService cluster) { instance = this; clusterService = cluster; }
        void release() { instance = null; }
    }
    static void serviceBindings() throws Exception {
        Navi navi = (Navi)U.allocateInstance(Navi.class);
        Cluster first = cluster(), second = cluster();
        navi.install(first);
        BAPBridge bridge = new BAPBridge(); set(bridge, "bapSessionStarted", true);
        bridge.refreshClusterAccess();
        check(first.container.isRgActive() && get(bridge, "csRef") == first, "initial lazy cluster acquire");
        first.container.setRgActive(false);
        first.komo = (KOMOService)U.allocateInstance(KOMOService.class);
        bridge.refreshClusterAccess();
        check(get(bridge, "komoService") == first.komo && first.container.isRgActive(), "same-cluster late KOMO acquire");
        navi.install(second);
        bridge.refreshClusterAccess();
        check(!first.container.isRgActive() && second.container.isRgActive()
            && get(bridge, "csRef") == second, "service replacement did not transfer native overlay");
        second.container.setRgActive(false);
        navi.install(null); bridge.refreshClusterAccess();
        check(!second.container.isRgActive() && get(bridge, "csRef") == null
            && get(bridge, "komoService") == null, "service disappearance retained stale binding");
        navi.install(first); bridge.refreshClusterAccess();
        check(first.container.isRgActive(), "service return was not acquired");
        bridge.disengageTakeover(); check(!first.container.isRgActive(), "disconnect leaked overlay");
        navi.release();
    }
    static final class Bridge extends BAPBridge {
        volatile int calls; volatile boolean fail = true;
        public void refreshClusterAccess() { }
        public boolean refreshInfoPresentation(RouteGuidance.State s, int phase) {
            calls++; check(phase == 1, "worker recovery reset Keep selection");
            if (fail) throw new IllegalStateException("presentation failure fixture");
            return true;
        }
        public boolean refreshRendererViewport() { return true; }
        public boolean isPresentationReady() { return false; }
        public void suspendPositionScroll() { }
    }
    static void workerRecovery() throws Exception {
        RouteGuidance rg = new RouteGuidance(); Bridge bridge = new Bridge();
        set(rg, "bap", bridge); set(rg, "running", true); set(rg, "rgActive", true);
        set(rg, "desiredInfoPhase", 1); set(rg, "keepInfoSelection", true);
        rg.refreshServiceBindings();
        long until = System.currentTimeMillis() + 2000;
        while ((bridge.calls == 0 || get(rg, "presentationThread") != null) && System.currentTimeMillis() < until)
            Thread.sleep(5);
        check(bridge.calls == 1 && get(rg, "presentationThread") == null, "failed worker not retired");
        int oldGeneration = (Integer)get(rg, "presentationGeneration");
        rg.refreshServiceBindings(); check(get(rg, "presentationThread") == null, "failure restart was not rate limited");
        bridge.fail = false; set(rg, "lastPresentationStartMs", 0L);
        rg.refreshServiceBindings();
        Thread worker = (Thread)get(rg, "presentationThread");
        until = System.currentTimeMillis() + 2000;
        while (bridge.calls < 2 && System.currentTimeMillis() < until) Thread.sleep(5);
        check(bridge.calls == 2 && worker != null && worker.isAlive(), "worker did not recover cached presentation");
        check((Long)get(rg, "infoReturnDeadline") == 0L && (Integer)get(rg, "desiredInfoPhase") == 1,
            "recovery changed Keep timeout/selection");
        Method loop = RouteGuidance.class.getDeclaredMethod("presentationLoop", int.class); loop.setAccessible(true);
        loop.invoke(rg, oldGeneration); check(bridge.calls == 2, "stale generation published");
        Object lock = get(rg, "presentationLock");
        synchronized (lock) {
            set(rg, "running", false);
            set(rg, "presentationGeneration", (Integer)get(rg, "presentationGeneration") + 1);
            lock.notifyAll();
        }
        worker.join(1000); check(!worker.isAlive(), "retired worker survived");
        rg.refreshServiceBindings(); check(!worker.isAlive(), "stopped route restarted worker");
    }
    public static void main(String[] args) throws Exception {
        Log.setLevel(-1); serviceBindings(); workerRecovery();
        System.out.println("RgiServiceRecoveryTest PASS");
    }
}
