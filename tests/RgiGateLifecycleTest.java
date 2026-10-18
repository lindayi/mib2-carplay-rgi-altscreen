package com.luka.carplay.core;

import com.luka.carplay.framework.Log;
import com.luka.carplay.routeguidance.GatedCombiService;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import de.audi.tghu.navi.app.Navigation;
import de.audi.tghu.navi.app.cluster.ClusterService;
import de.audi.tghu.navi.app.cluster.CombiBAPListener;
import de.esolutions.fw.util.commons.job.DispatcherBase;
import de.esolutions.fw.util.commons.job.Job;
import java.lang.reflect.*;
import java.util.*;
import sun.misc.Unsafe;

public final class RgiGateLifecycleTest {
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
    static void set(Object o, String name, Object v) throws Exception { field(o.getClass(), name).set(o, v); }
    static void gateField(String name, Object v) throws Exception { field(ScreenNavStatusGate.class, name).set(null, v); }
    static class Queue extends DispatcherBase {
        List<Runnable> tasks; boolean dispatching, reject;
        Queue() { super(null, null, null, null); }
        public Job execute(Object o) {
            if (reject) throw new IllegalStateException("rejected fixture");
            tasks.add((Runnable)o); return null;
        }
        void drain() {
            dispatching = true;
            try { while (!tasks.isEmpty()) tasks.remove(0).run(); }
            finally { dispatching = false; }
        }
    }
    static class Navi extends Navigation {
        Navi() { super(null); }
        void install(Queue queue, ClusterService cluster) {
            instance = this; dispatcher = queue; clusterService = cluster;
        }
    }
    static class Capture implements InvocationHandler {
        final Map<String,Object[]> values = new HashMap<>();
        int writes, failCount; String fail;
        Queue queue; boolean requireQueue;
        public Object invoke(Object p, Method m, Object[] a) {
            if (m.getDeclaringClass() == Object.class) {
                if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                if (m.getName().equals("equals")) return p == a[0];
                return "BAP fixture";
            }
            check(!requireQueue || queue.dispatching, "OEM replay outside NavigationJobs: " + m.getName());
            if (m.getName().equals(fail) && failCount-- > 0) throw new IllegalStateException("fixture replay failure");
            writes++; values.put(m.getName(), a); return null;
        }
        CombiBAPServiceNavi service() {
            return (CombiBAPServiceNavi)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{CombiBAPServiceNavi.class}, this);
        }
    }
    static void boundedReplay() {
        Capture c = new Capture(); GatedCombiService g = new GatedCombiService(c.service());
        g.setRouteGuidanceBlocked(true); g.setCurrentPositionInfoBlocked(true);
        for (int i = 0; i < 1000; i++) g.updateRGStatus(i);
        g.updateCurrentPositionInfo("latest native road");
        g.updateTurnToInfo("next", "exit");
        g.updateDistanceToDestination(17, 1, false);
        g.updateTimeToDestination(1, 2, 99L);
        check(c.writes == 0, "blocked fields escaped");
        c.fail = "updateRGStatus"; c.failCount = 1;
        g.setRouteGuidanceBlocked(false);
        check(g.hasPendingReplay(), "failed replay not retained");
        g.setRouteGuidanceBlocked(true);
        check(!g.hasPendingReplay(), "failed prior release prevented renewed CarPlay ownership");
        g.updateRGStatus(1000);
        g.setRouteGuidanceBlocked(false);
        check(!g.hasPendingReplay() && ((Integer)c.values.get("updateRGStatus")[0]) == 1000, "latest route replay");
        check(!c.values.containsKey("updateCurrentPositionInfo"), "route release stole current-position ownership");
        g.setCurrentPositionInfoBlocked(false);
        check(c.values.get("updateCurrentPositionInfo")[0].equals("latest native road")
            && ((Long)c.values.get("updateTimeToDestination")[2]) == 99L, "position replay lost");
        int writes = c.writes; g.setCurrentPositionInfoBlocked(false);
        check(c.writes == writes, "duplicate release replayed successful values");
        g.setRouteGuidanceBlocked(true); g.updateRGStatus(4); g.retire(); g.setRouteGuidanceBlocked(false);
        g.updateRGStatus(5);
        check(c.writes == writes, "retired service published stale state");
    }
    static void upstreamTextOnlyReplay() {
        Capture c = new Capture(); GatedCombiService g = new GatedCombiService(c.service());
        g.setCurrentPositionInfoBlocked(true);
        g.updateCurrentPositionInfo("Audi road");
        g.updateDistanceToDestination(55, 1, false);
        g.updateTimeToDestination(1, 2, 66L);
        g.setCurrentPositionTextOnly(true);
        check(!c.values.containsKey("updateCurrentPositionInfo")
            && ((Integer)c.values.get("updateDistanceToDestination")[0]) == 55
            && ((Long)c.values.get("updateTimeToDestination")[2]) == 66L,
            "upstream text-only transition did not restore only the released OEM fields");
        g.setCurrentPositionInfoBlocked(false);
        check(c.values.get("updateCurrentPositionInfo")[0].equals("Audi road"),
            "text-only release did not restore the held road");
    }
    static void upstreamTextOnlyBeforeBlock() {
        Capture c = new Capture(); GatedCombiService g = new GatedCombiService(c.service());
        g.setCurrentPositionTextOnly(true);
        g.setCurrentPositionInfoBlocked(true);
        g.updateCurrentPositionInfo("held road");
        g.updateDistanceToDestination(55, 1, false);
        g.updateTimeToDestination(1, 2, 66L);
        check(!c.values.containsKey("updateCurrentPositionInfo")
            && c.values.containsKey("updateDistanceToDestination")
            && c.values.containsKey("updateTimeToDestination"),
            "upstream text-only-before-block order lost stock travel fields");
        g.setCurrentPositionInfoBlocked(false);
        check(c.values.get("updateCurrentPositionInfo")[0].equals("held road"),
            "upstream ordered release did not restore road");
        c.values.clear();
        g.setCurrentPositionInfoBlocked(true);
        g.updateDistanceToDestination(77, 1, false);
        check(!c.values.containsKey("updateDistanceToDestination"),
            "release retained text-only policy for a later full block");
    }
    public static void main(String[] args) throws Exception {
        Log.setLevel(-1); boundedReplay(); upstreamTextOnlyReplay(); upstreamTextOnlyBeforeBlock();
        Queue q = (Queue)U.allocateInstance(Queue.class); q.tasks = new ArrayList<>();
        Navi navi = (Navi)U.allocateInstance(Navi.class);
        ClusterService cs = (ClusterService)U.allocateInstance(ClusterService.class);
        CombiBAPListener listener = (CombiBAPListener)U.allocateInstance(CombiBAPListener.class);
        set(cs, "combiBAPListener", listener); navi.install(q, cs);
        Capture first = new Capture(); first.queue = q; first.requireQueue = true;
        set(listener, "combiservice", first.service());
        set(listener, "largeMapView", true); set(listener, "supplementaryMapViewVisible", true);
        check(!ScreenNavStatusGate.setRouteGuidanceBlocked(true) && q.tasks.size() == 1, "installation not asynchronous");
        ScreenNavStatusGate.setCurrentPositionInfoBlocked(true);
        q.drain();
        check(ScreenNavStatusGate.setRouteGuidanceBlocked(true), "installed policy not acknowledged");
        GatedCombiService gate = (GatedCombiService)cs.getCombiBAPListenerCombiService();
        check(Boolean.TRUE.equals(first.values.get("updateMapPresentation")[0])
            && Boolean.TRUE.equals(first.values.get("updateMapVisibility")[1]), "actual stock accepted startup state not seeded");
        check(field(SpeedBadge.class, "wideMap").getBoolean(null)
            && field(com.luka.carplay.settings.VcPanel.class, "largeMap").getBoolean(null),
            "startup replay dropped speed-badge or VC-panel eligibility");
        gate.updateRGStatus(8); gate.updateCurrentPositionInfo("held");
        int before = first.writes;
        ScreenNavStatusGate.setRouteGuidanceBlocked(false);
        check(first.writes == before && q.tasks.size() == 1, "release ran synchronously");
        first.fail = "updateCurrentPositionInfo"; first.failCount = 1;
        q.drain(); check(gate.hasPendingReplay(), "failed queued replay lost");
        gateField("lastRetryMs", 0L); ScreenNavStatusGate.retryPending(false); q.drain();
        check(!gate.hasPendingReplay() && first.values.get("updateCurrentPositionInfo")[0].equals("held"),
            "disconnected resident replay did not recover");

        ScreenNavStatusGate.setRouteGuidanceBlocked(true);
        Runnable stale = q.tasks.remove(0);
        gateField("installScheduledAt", System.currentTimeMillis() - 6000L);
        gateField("lastRetryMs", 0L); ScreenNavStatusGate.retryPending(true);
        check(q.tasks.size() == 1, "expired task not replaced");
        stale.run(); check(q.tasks.size() == 1, "late ticket consumed new task");
        q.drain();
        gate.updateRGStatus(19);
        Capture second = new Capture(); second.queue = q; second.requireQueue = true;
        q.dispatching = true;
        CombiBAPServiceNavi next = ScreenNavStatusGate.prepareCombiBAPService(cs, second.service());
        set(listener, "combiservice", next);
        q.dispatching = false;
        int retiredWrites = first.writes;
        gate.updateRGStatus(20); gate.setRouteGuidanceBlocked(false);
        check(first.writes == retiredWrites, "service replacement revived retired OEM writes");
        next.updateRGStatus(21);
        ScreenNavStatusGate.setRouteGuidanceBlocked(false); q.drain();
        check(((Integer)second.values.get("updateRGStatus")[0]) == 21, "replacement lost its own snapshot");
        q.reject = true; ScreenNavStatusGate.setRouteGuidanceBlocked(true);
        q.reject = false; gateField("lastRetryMs", 0L); ScreenNavStatusGate.retryPending(true); q.drain();
        check(ScreenNavStatusGate.setRouteGuidanceBlocked(true), "dispatcher rejection stranded policy");
        set(listener, "combiservice", null);
        gateField("lastRetryMs", 0L); ScreenNavStatusGate.retryPending(true); q.drain();
        check(field(ScreenNavStatusGate.class, "gate").get(null) == null, "missing service retained old wrapper");
        int removedWrites = second.writes; next.updateRGStatus(30);
        check(second.writes == removedWrites, "removed service accepted late route state");
        set(listener, "combiservice", second.service());
        gateField("lastRetryMs", 0L); ScreenNavStatusGate.retryPending(true); q.drain();
        check(ScreenNavStatusGate.setRouteGuidanceBlocked(true), "service return not installed");
        System.out.println("RgiGateLifecycleTest PASS");
    }
}
