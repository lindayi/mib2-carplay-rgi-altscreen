/*
 * CarPlay Hook - Main Entry Point
 *
 * Upstream lifecycle, RGI initialization, device listener and service discovery.
 * MU1316 extensions: always-on transport, preference gates and local Module hooks.
 * OEM callbacks publish intent; one actor also drains runtime monitoring/retries.
 */
package com.luka.carplay;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.framework.Log;
import com.luka.carplay.framework.ElapsedClock;
import com.luka.carplay.core.CarPlayApp;
import com.luka.carplay.core.FrameworkRef;
import com.luka.carplay.core.LifecycleFeatures;
import com.luka.carplay.core.Module;
import com.luka.carplay.core.ScreenNavStatusGate;
import com.luka.carplay.pdc.PdcSmallStageGuard;
import com.luka.carplay.routeguidance.RouteGuidance;
import com.luka.carplay.routeguidance.LowerBarKomo;
import de.audi.app.terminalmode.IContext;
import de.audi.app.terminalmode.device.IDeviceManager;
import de.audi.app.terminalmode.device.TMDevice;
import de.audi.app.terminalmode.osgi.IServiceManager;
import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import org.osgi.framework.ServiceReference;

public final class CarPlayHook {
    private static final String TAG = "CarPlayHook";
    private static volatile boolean active, registered, carplayRunning;
    private static volatile FrameworkRef fwRef;
    private static final Object LIFECYCLE_QUEUE = new Object();
    private static Thread lifecycleWorker;
    private static IContext requestedContext, phoneContext;
    private static Object savedContext;
    private static int activationGeneration, appliedGeneration, serviceGeneration, appliedServiceGeneration;
    private static boolean featuresEnabled = true, guidanceEnabled = true;
    private static long deactivateDeadline;
    private static final Module[] MODULES = LifecycleFeatures.create();
    private static final boolean[] started = new boolean[MODULES.length];
    private static final boolean[] stopPending = new boolean[MODULES.length];
    private static DeviceStateHandler listenerProxy;
    private static IDeviceManager listenerManager;
    private static RouteGuidance routeGuidance;
    private static IServiceManager naviServiceManager;
    private static ServiceReference naviServiceReference;
    private static Object boundNaviService, boundNaviUnderlyingService, naviServiceIdentity;
    private static long lastServiceProbe, lastInitFailureLog, lastDiscoveryWarning;
    private static boolean retrying, retiringRouteGuidance, naviReleasePending;
    private static int listenerGeneration, retryAttempts;
    private static final java.util.Vector pendingReleases = new java.util.Vector();

    private CarPlayHook() {}

    /* ============================================================
     * Entry Points (called from hook)
     * ============================================================ */

    public static void startTransport(Object context) {
        CarplayBus.getInstance().start();
        synchronized (LIFECYCLE_QUEUE) {
            if (context instanceof IContext && requestedContext == null && !active)
                requestedContext = (IContext)context;
            ensureLifecycleWorker();
        }
        Log.i(TAG, "transport up (bus always-on) build=" + CarPlayApp.BUILD_ID);
    }

    public static void onActivate(Object context) {
        if (!(context instanceof IContext)) {
            Log.e(TAG, "context is not IContext: " + context);
            return;
        }
        synchronized (LIFECYCLE_QUEUE) {
            phoneContext = (IContext)context;
            if (featuresEnabled) queueLifecycle((IContext)context, true);
        }
    }

    public static void onDeactivate() {
        synchronized (LIFECYCLE_QUEUE) {
            phoneContext = null;
            queueLifecycle(null, false);
        }
    }

    public static void onDeactivateAndWait() {
        synchronized (LIFECYCLE_QUEUE) {
            phoneContext = null;
            queueLifecycle(null, false);
            int generation = activationGeneration;
            long deadline = ElapsedClock.nowMs() + 3000L;
            while (appliedGeneration != generation && activationGeneration == generation) {
                long remaining = deadline - ElapsedClock.nowMs();
                if (remaining <= 0L) {
                    Log.w(TAG, "deactivate cleanup timed out generation=" + generation);
                    break;
                }
                try { LIFECYCLE_QUEUE.wait(remaining); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    /* Caller holds only the small publication lock, never a module/service lock. */
    private static void queueLifecycle(IContext context, boolean activate) {
        ensureLifecycleWorker();
        if (active == activate && requestedContext == context) return;
        active = activate;
        if (!activate) carplayRunning = false;
        requestedContext = context;
        ++activationGeneration;
        ++serviceGeneration;
        deactivateDeadline = activate ? 0L : ElapsedClock.nowMs() + 400L;
        LIFECYCLE_QUEUE.notifyAll();
    }

    private static void ensureLifecycleWorker() {
        if (lifecycleWorker != null && lifecycleWorker.isAlive()) return;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    int generation, service;
                    IContext context;
                    boolean activate, changed;
                    synchronized (LIFECYCLE_QUEUE) {
                        long wait = !active && appliedGeneration != activationGeneration
                            ? deactivateDeadline - ElapsedClock.nowMs() : 0L;
                        if (wait > 0L || (appliedGeneration == activationGeneration
                                && appliedServiceGeneration == serviceGeneration)) {
                            try { LIFECYCLE_QUEUE.wait(wait > 0L ? wait : 200L); }
                            catch (InterruptedException ignored) { }
                            if (!active && ElapsedClock.nowMs() < deactivateDeadline) continue;
                        }
                        generation = activationGeneration;
                        service = serviceGeneration;
                        context = requestedContext;
                        activate = active;
                        changed = appliedGeneration != generation;
                    }
                    try {
                        if (changed) {
                            deactivateNow();
                            if (activate && current(generation, context)) activateNow(context, generation);
                        } else if (!activate && fwRef == null && context != null) {
                            fwRef = new FrameworkRef(context);
                        }
                        if (current(generation, context)) {
                            if (service != appliedServiceGeneration && !changed) {
                                retireRouteGuidance();
                                releaseNaviService();
                                lastServiceProbe = 0L;
                                retryAttempts = 0;
                            }
                            pollRgiRuntimeNow(generation, context);
                        }
                    } catch (Throwable t) {
                        retrying = activate;
                        Log.e(TAG, "lifecycle worker: " + t);
                    } finally {
                        synchronized (LIFECYCLE_QUEUE) {
                            appliedGeneration = generation;
                            appliedServiceGeneration = service;
                            LIFECYCLE_QUEUE.notifyAll();
                        }
                    }
                }
            }
        }, "carplay-oem-lifecycle");
        worker.setDaemon(true);
        lifecycleWorker = worker;
        try { worker.start(); }
        catch (Throwable t) { lifecycleWorker = null; Log.e(TAG, "lifecycle worker start failed; intent retained: " + t); }
    }

    private static boolean current(int generation, IContext context) {
        synchronized (LIFECYCLE_QUEUE) {
            return generation == activationGeneration && requestedContext == context;
        }
    }

    private static void activateNow(IContext context, int generation) {
        if (cleanupPending()) return;
        Log.refreshLevel();
        Log.i(TAG, "onActivate build=" + CarPlayApp.BUILD_ID);
        FrameworkRef next = new FrameworkRef(context);
        if (!current(generation, context)) return;
        fwRef = next;
        savedContext = context;
        CarplayBus.getInstance().start();
        lastServiceProbe = 0L;
        retrying = true;
        retryAttempts = 0;
    }

    private static void deactivateNow() {
        Log.i(TAG, "onDeactivate");
        retrying = false;
        for (int i = MODULES.length - 1; i > 0; i--) stopFeature(i);
        retireRouteGuidance();
        releaseNaviService();
        unregisterListener();
        stopFeature(0);
        try { PdcSmallStageGuard.carPlayDisconnected(); }
        catch (Throwable t) { Log.w(TAG, "OPS presentation cleanup: " + t); }
        savedContext = null;
        if (!cleanupPending()) fwRef = null;
        /* Bus deliberately survives session teardown; input's stop flushes gestures. */
    }

    private static void stopFeature(int index) {
        stopPending[index] = true;
        try { MODULES[index].stop(); stopPending[index] = false; }
        catch (Throwable t) { Log.w(TAG, MODULES[index].name() + " stop: " + t); }
        started[index] = false;
    }

    private static boolean startFeature(int index, int generation, IContext context) {
        if (!active || !current(generation, context)) return false;
        if (stopPending[index]) {
            stopFeature(index);
            if (stopPending[index]) return false;
        }
        if (started[index]) return true;
        boolean ok;
        try { ok = MODULES[index].start(fwRef); }
        catch (Throwable t) {
            Log.w(TAG, MODULES[index].name() + " start: " + t);
            stopFeature(index);
            return false;
        }
        if (!active || !current(generation, context)) {
            stopFeature(index);
            return false;
        }
        started[index] = ok;
        return ok;
    }

    /* ============================================================
     * Initialization
     * ============================================================ */

    private static boolean tryInit(IContext context, int generation) {
        if (!active || !current(generation, context)) return false;
        if (cleanupPending()) return false;
        if (savedContext != context || fwRef == null) activateNow(context, generation);
        if (fwRef == null || !fwRef.isReady()) return false;
        if (!startFeature(0, generation, context)) return false;
        if (!initNow(context, generation)) return false;
        for (int i = 1; i < MODULES.length; i++)
            if (!startFeature(i, generation, context)) return false;
        return true;
    }

    private static boolean initNow(IContext context, int generation) {
        int service;
        synchronized (LIFECYCLE_QUEUE) { service = serviceGeneration; }
        if (retiringRouteGuidance) {
            retireRouteGuidance();
            if (retiringRouteGuidance || !releaseNaviService()) return false;
        }
        IDeviceManager deviceManager = getDeviceManager(context);
        if (deviceManager == null || !registerListener(deviceManager, generation)) return false;
        if (!active || !current(generation, context)) return false;
        if (!LifecycleFeatures.isPlatformSupported(fwRef)) return true;
        Object naviService = getNaviService(context);
        if (!rgiCurrent(generation, context, service)) {
            retireRouteGuidance(); releaseNaviService(); return false;
        }
        if (naviService == null) return false;
        boolean textOnly = isRgiPresentationDisabled();
        if (routeGuidance != null && routeGuidance.isTextOnly() != textOnly) {
            retireRouteGuidance();
            if (retiringRouteGuidance) return false;
        }
        if (routeGuidance == null) {
            routeGuidance = new RouteGuidance(textOnly);
            boolean initialized;
            try { initialized = routeGuidance.init(naviService); }
            catch (Throwable t) {
                Log.e(TAG, "RouteGuidance init error: " + t);
                retireRouteGuidance();
                return false;
            }
            if (!initialized) {
                long now = ElapsedClock.nowMs();
                if (lastInitFailureLog == 0L || now < lastInitFailureLog || now - lastInitFailureLog >= 10000L) {
                    lastInitFailureLog = now;
                    Log.e(TAG, "RouteGuidance init failed; retry pending");
                }
                retireRouteGuidance();
                return false;
            }
        }
        if (!rgiCurrent(generation, context, service)) { retireRouteGuidance(); return false; }
        if (!routeGuidance.engageTakeover()) return false;
        if (!rgiCurrent(generation, context, service)) { retireRouteGuidance(); return false; }
        if (!routeGuidance.isRunning()) {
            try { routeGuidance.start(); }
            catch (Throwable t) {
                Log.e(TAG, "RouteGuidance start error: " + t);
                retireRouteGuidance();
                return false;
            }
        }
        if (!rgiCurrent(generation, context, service)) { retireRouteGuidance(); return false; }
        return true;
    }

    private static boolean rgiCurrent(int generation, IContext context, int service) {
        synchronized (LIFECYCLE_QUEUE) {
            return active && generation == activationGeneration && context == requestedContext
                && service == serviceGeneration;
        }
    }

    /* ============================================================
     * Device State Callback
     * ============================================================ */

    public static void onDeviceStateUpdate(TMDevice device) {
        int generation;
        synchronized (LIFECYCLE_QUEUE) { generation = activationGeneration; }
        onDeviceStateUpdate(device, generation);
    }

    static void onDeviceStateUpdate(TMDevice device, int generation) {
        synchronized (LIFECYCLE_QUEUE) {
            if (!active || generation != activationGeneration) return;
            /* Inactive/unselected during parking is not a physical disconnect. */
            carplayRunning = device != null && device.isCarplayDevice()
                && device.isActive() && device.isSelected();
        }
    }

    /* ============================================================
     * State Accessors and local live preferences
     * ============================================================ */

    public static boolean isCarplayRunning() { return carplayRunning; }
    public static IFrameworkAccess getFrameworkAccess() {
        FrameworkRef ref = fwRef;
        return ref == null ? null : ref.framework();
    }
    public static boolean isActive() { return active; }
    public static boolean isRegistered() { return registered; }
    public static boolean isSessionConnected() {
        synchronized (LIFECYCLE_QUEUE) { return phoneContext != null; }
    }
    public static boolean isGuidanceEnabled() {
        synchronized (LIFECYCLE_QUEUE) { return guidanceEnabled; }
    }
    private static boolean isRgiPresentationDisabled() { return !isGuidanceEnabled(); }
    public static void setFeaturesEnabled(boolean enabled) {
        synchronized (LIFECYCLE_QUEUE) {
            if (featuresEnabled == enabled) return;
            featuresEnabled = enabled;
            if (!enabled) queueLifecycle(null, false);
            else if (phoneContext != null) queueLifecycle(phoneContext, true);
        }
    }
    public static void setGuidanceEnabled(boolean enabled) {
        synchronized (LIFECYCLE_QUEUE) {
            if (guidanceEnabled == enabled) return;
            guidanceEnabled = enabled;
            onNavigationServiceChanged();
        }
    }
    public static void onNavigationServiceChanged() {
        synchronized (LIFECYCLE_QUEUE) {
            if (!active) return;
            ++serviceGeneration;
            LIFECYCLE_QUEUE.notifyAll();
        }
    }

    /* Upstream's independent monitor/retry are drained by the same actor. */
    public static void pollRgiRuntime() {
        synchronized (LIFECYCLE_QUEUE) { LIFECYCLE_QUEUE.notifyAll(); }
    }
    private static void pollRgiRuntimeNow(int generation, IContext context) {
        ScreenNavStatusGate.retryPending(active);
        LowerBarKomo.retryPending();
        retryServiceReleases();
        for (int i = MODULES.length - 1; i >= 0; i--)
            if (stopPending[i]) stopFeature(i);
        if (retiringRouteGuidance) retireRouteGuidance();
        if (naviReleasePending) releaseNaviService();
        if (listenerProxy != null && listenerGeneration != generation) unregisterListener();
        if (!active) { retireRouteGuidance(); releaseNaviService(); unregisterListener(); return; }
        long now = ElapsedClock.nowMs();
        long delay = retrying ? (retryAttempts < 50 ? 200L : 2000L) : 1000L;
        if (lastServiceProbe != 0L && now >= lastServiceProbe && now - lastServiceProbe < delay) return;
        lastServiceProbe = now;
        retrying = !tryInit(context, generation);
        if (retrying) retryAttempts++; else retryAttempts = 0;
        if (active && current(generation, context) && routeGuidance != null
                && !retiringRouteGuidance && !naviReleasePending)
            routeGuidance.refreshServiceBindings();
    }

    private static boolean cleanupPending() {
        for (int i = 0; i < stopPending.length; i++) if (stopPending[i]) return true;
        return retiringRouteGuidance || naviReleasePending || !pendingReleases.isEmpty()
            || (listenerProxy != null && listenerGeneration != activationGeneration);
    }

    /* ============================================================
     * Service Discovery
     * ============================================================ */

    private static void discoveryWarning(String message) {
        long now = ElapsedClock.nowMs();
        if (lastDiscoveryWarning != 0L && now >= lastDiscoveryWarning && now - lastDiscoveryWarning < 10000L) return;
        lastDiscoveryWarning = now;
        Log.w(TAG, message);
    }

    private static Object getNaviService(Object context) {
        if (!pendingReleases.isEmpty()) return null;
        try {
            IServiceManager sm = ((IContext)context).getServiceManager();
            ServiceReference[] refs = sm == null ? null : sm.getServiceReferences(CombiBAPServiceNavi.class);
            if (refs != null) for (int i = 0; i < refs.length; i++) {
                ServiceReference ref = refs[i];
                if (ref == null) continue;
                Object identity = ref.getProperty("service.id");
                if (boundNaviService != null && sm == naviServiceManager && identity != null
                        && identity.equals(naviServiceIdentity)) return boundNaviService;
                Object next = sm.getService(ref);
                if (next == null) continue;
                Object underlying = identity == null ? probeUnderlyingNaviService(sm, ref) : null;
                if (next == boundNaviService || (underlying != null && underlying == boundNaviUnderlyingService)) {
                    releaseProbe(sm, ref);
                    return boundNaviService;
                }
                retireRouteGuidance();
                if (!releaseNaviService()) {
                    releaseProbe(sm, ref);
                    return null;
                }
                naviServiceManager = sm;
                naviServiceReference = ref;
                boundNaviService = next;
                boundNaviUnderlyingService = underlying;
                naviServiceIdentity = identity;
                return next;
            }
            retireRouteGuidance();
            releaseNaviService();
        } catch (Throwable e) {
            discoveryWarning("getNaviService: " + e);
            retireRouteGuidance();
            releaseNaviService();
        }
        return null;
    }

    /* Audi's service manager may return a fresh logging wrapper for the same service. */
    private static Object probeUnderlyingNaviService(IServiceManager sm, ServiceReference reference) {
        org.osgi.framework.BundleContext context = null;
        Object service = null;
        try {
            Object value = sm.getClass().getMethod("getBundleContext", new Class[0]).invoke(sm, new Object[0]);
            if (!(value instanceof org.osgi.framework.BundleContext)) return null;
            context = (org.osgi.framework.BundleContext)value;
            service = context.getService(reference);
            return service;
        } catch (Throwable t) {
            return null;
        } finally {
            if (context != null && service != null) {
                releaseProbe(context, reference);
            }
        }
    }

    private static void releaseProbe(Object manager, ServiceReference reference) {
        pendingReleases.addElement(new Object[] { manager, reference });
        retryServiceReleases();
    }

    private static void retryServiceReleases() {
        for (int i = pendingReleases.size() - 1; i >= 0; i--) {
            Object[] pair = (Object[])pendingReleases.elementAt(i);
            try {
                if (pair[0] instanceof IServiceManager)
                    ((IServiceManager)pair[0]).releaseService((ServiceReference)pair[1]);
                else ((org.osgi.framework.BundleContext)pair[0]).ungetService((ServiceReference)pair[1]);
                pendingReleases.removeElementAt(i);
            } catch (Throwable t) { discoveryWarning("navigation probe release pending: " + t); }
        }
    }

    private static void retireRouteGuidance() {
        RouteGuidance old = routeGuidance;
        if (old == null) return;
        retiringRouteGuidance = true;
        try { old.stop(); old.disengageTakeover(); routeGuidance = null; retiringRouteGuidance = false; }
        catch (Throwable t) { Log.e(TAG, "RGI service retirement error: " + t); }
    }

    private static boolean releaseNaviService() {
        if (routeGuidance != null) return false;
        naviReleasePending = true;
        if (naviServiceManager != null && naviServiceReference != null) {
            try { naviServiceManager.releaseService(naviServiceReference); }
            catch (Throwable t) { Log.w(TAG, "navigation service release failed: " + t); return false; }
        }
        naviServiceManager = null;
        naviServiceReference = null;
        boundNaviService = boundNaviUnderlyingService = naviServiceIdentity = null;
        naviReleasePending = false;
        return true;
    }

    private static IDeviceManager getDeviceManager(Object context) {
        try { return ((IContext)context).getDeviceManager(); }
        catch (Throwable e) { discoveryWarning("getDeviceManager: " + e); return null; }
    }

    /* ============================================================
     * Listener Registration
     * ============================================================ */

    private static boolean registerListener(IDeviceManager dm, int generation) {
        if (registered && listenerManager == dm && listenerGeneration == generation) return true;
        if (!unregisterListener()) return false;
        DeviceStateHandler handler = new DeviceStateHandler(generation);
        listenerProxy = handler;
        listenerManager = dm;
        listenerGeneration = generation;
        try {
            dm.addActiveDeviceListener(handler);
            registered = true;
            Log.i(TAG, "Listener registered");
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "registerListener error: " + e);
            unregisterListener();
            return false;
        }
    }

    private static boolean unregisterListener() {
        if (listenerManager != null && listenerProxy != null) {
            try { listenerManager.removeActiveDeviceListener(listenerProxy); }
            catch (Throwable e) { Log.e(TAG, "unregisterListener error: " + e); return false; }
        }
        registered = false;
        listenerProxy = null;
        listenerManager = null;
        return true;
    }
}
