/*
 * ScreenNavStatusGate - installs the single wrapper that prevents stock
 * route-guidance BAP writes from racing CarPlay RGI.  It deliberately does not
 * alter NAVSD availability, initializing state, compass, scale or map status.
 */
package com.luka.carplay.core;

import com.luka.carplay.framework.Log;
import com.luka.carplay.routeguidance.GatedCombiService;

import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import de.audi.tghu.navi.app.Navigation;
import de.audi.tghu.navi.app.cluster.ClusterService;

public final class ScreenNavStatusGate {
    private static final String TAG = "StatusGate";

    private static ClusterService clusterService;
    private static GatedCombiService gate;
    private static boolean desiredRouteBlocked;
    private static boolean appliedRouteBlocked;
    private static boolean desiredCurrentPositionBlocked;
    private static boolean desiredCurrentPositionTextOnly;
    private static boolean installScheduled;
    private static int installTicket;
    private static int policyRevision;
    private static int appliedRevision = -1;
    private static long installScheduledAt, lastRetryMs, lastWarning;
    private static final long INSTALL_TIMEOUT_MS = 5000L;

    private ScreenNavStatusGate() { }

    /** All reads/writes of CombiBAPListener.combiservice must happen on NavigationJobs. */
    private static void scheduleInstallLocked() {
        long now = System.currentTimeMillis();
        if (installScheduled && (now < installScheduledAt || now - installScheduledAt >= INSTALL_TIMEOUT_MS)) {
            installScheduled = false;
            ++installTicket;
            appliedRevision = -1;
            warn("NavigationJobs task expired", null);
        }
        if (installScheduled || (gate != null && appliedRevision == policyRevision && !gate.hasPendingReplay())) return;
        Navigation navigation = Navigation.getInstance();
        if (navigation == null || navigation.getDispatcher() == null) return;
        installScheduled = true;
        installScheduledAt = now;
        final int ticket = ++installTicket;
        try {
            navigation.getDispatcher().execute(new Runnable() {
                public void run() { installNow(ticket); }
            });
        } catch (Throwable t) {
            installScheduled = false;
            warn("NavigationJobs submission failed", t);
        }
    }

    private static void warn(String message, Throwable t) {
        long now = System.currentTimeMillis();
        if (lastWarning != 0L && now >= lastWarning && now - lastWarning < 10000L) return;
        lastWarning = now;
        try { Log.w(TAG, message + (t == null ? "" : ": " + t)); } catch (Throwable ignored) { }
    }

    private static synchronized void installNow(int ticket) {
        if (!installScheduled || ticket != installTicket) return;
        installScheduled = false;
        try {
            Navigation navigation = Navigation.getInstance();
            ClusterService cs = navigation == null ? null : navigation.getClusterService();
            CombiBAPServiceNavi current = cs == null ? null : cs.getCombiBAPListenerCombiService();
            if (current == null) {
                if (gate != null) {
                    gate.retire();
                    GatedCombiService.publishMapVisibility(false);
                }
                gate = null;
                clusterService = null;
                appliedRouteBlocked = false;
                appliedRevision = -1;
                return;
            }

            if (cs != clusterService || gate == null || current != gate) {
                GatedCombiService previous = gate;
                if (current instanceof GatedCombiService) {
                    gate = (GatedCombiService) current;
                } else {
                    gate = new GatedCombiService(current);
                }
                if (previous != null && previous != gate) previous.retire();
                gate.setRouteGuidanceBlocked(desiredRouteBlocked);
                gate.setCurrentPositionInfoBlocked(desiredCurrentPositionBlocked,desiredCurrentPositionTextOnly);
                if (current != gate) cs.setCombiBAPListenerCombiService(gate);
                clusterService = cs;
                seedAcceptedState(cs);
                Log.i(TAG, "native route-guidance gate installed/reused");
            }

            gate.setRouteGuidanceBlocked(desiredRouteBlocked);
            gate.setCurrentPositionInfoBlocked(desiredCurrentPositionBlocked,desiredCurrentPositionTextOnly);
            appliedRouteBlocked = desiredRouteBlocked;
            appliedRevision = gate.hasPendingReplay() ? -1 : policyRevision;
        } catch (Throwable t) {
            appliedRevision = -1;
            warn("navigation gate installation failed", t);
        }
    }

    /** Late installation must capture accepted stock state without guessing values.
     * Invoke the stock publisher methods only on NavigationJobs. In particular the
     * private presentation publisher covers startup replay and both drawer flags. */
    private static void seedAcceptedState(ClusterService cs) {
        String[] names = {"updateMapPresentation", "updateMapVisibility",
            "updateRGStatusAndType", "updateActiveRGType", "updateDistanceToNextManeuver",
            "updateCurrentPositionInfo", "updateManeuverDescriptor", "updateLaneGuidance",
            "updateExitView", "updateManeuverState", "updateTurnToInfo",
            "updateDistanceToDestination", "updateTimeToDestination", "updateDestinationInfo"};
        GatedCombiService.publishMapVisibility(false);
        try {
            Object listener = cs.getClass().getMethod("getCombiBAPListener", new Class[0])
                .invoke(cs, new Object[0]);
            if (listener == null) return;
            for (int i = 0; i < names.length; i++) {
                Class type = listener.getClass();
                java.lang.reflect.Method method = null;
                while (type != null) {
                    try { method = type.getDeclaredMethod(names[i], new Class[0]); break; }
                    catch (NoSuchMethodException absent) { type = type.getSuperclass(); }
                }
                try {
                    if (method == null) throw new NoSuchMethodException(names[i]);
                    method.setAccessible(true);
                    method.invoke(listener, new Object[0]);
                } catch (Throwable t) { warn("OEM snapshot seed failed: " + names[i], t); }
            }
        } catch (Throwable t) { warn("OEM snapshot accessor unavailable", t); }
    }

    /** Called synchronously by ClusterService#setCombiBAPService on NavigationJobs, before the
     * stock CombiBAPListener setter performs updateAll().  Installing here closes the old race in
     * which updateAll painted native INITIALIZING/status values before the asynchronous wrapper
     * appeared, and avoids cross-thread writes to CombiBAPListener.combiservice. */
    public static synchronized CombiBAPServiceNavi prepareCombiBAPService(
            ClusterService cs, CombiBAPServiceNavi incoming) {
        if (incoming == null) {
            if (cs == clusterService && gate != null) {
                gate.retire();
                clusterService = null;
                gate = null;
                appliedRouteBlocked = false;
                appliedRevision = -1;
                installScheduled = false;
                ++installTicket;
                GatedCombiService.publishMapVisibility(false);
            }
            return null;
        }

        GatedCombiService next;
        if (incoming instanceof GatedCombiService) {
            next = (GatedCombiService) incoming;
        } else {
            next = new GatedCombiService(incoming);
        }
        if (gate != null && gate != next) {
            gate.retire();
        }
        clusterService = cs;
        gate = next;
        gate.setRouteGuidanceBlocked(desiredRouteBlocked);
        gate.setCurrentPositionInfoBlocked(desiredCurrentPositionBlocked,desiredCurrentPositionTextOnly);
        appliedRouteBlocked = desiredRouteBlocked;
        appliedRevision = gate.hasPendingReplay() ? -1 : policyRevision;
        installScheduled = false;
        ++installTicket;
        Log.i(TAG, "native route-guidance gate installed on Navigation service edge");
        return gate;
    }

    /** Single owner of the native CombiBAP wrapper for both the cluster module and RGI. */
    public static synchronized boolean setRouteGuidanceBlocked(boolean blocked) {
        if (desiredRouteBlocked != blocked || (!blocked && desiredCurrentPositionBlocked)) ++policyRevision;
        desiredRouteBlocked = blocked;
        // Tightening only changes policy; releasing replays OEM data on NavigationJobs.
        if (blocked && gate != null) gate.setRouteGuidanceBlocked(true);
        if (!blocked) {
            desiredCurrentPositionBlocked = false;
            desiredCurrentPositionTextOnly = false;
        }
        scheduleInstallLocked();
        return gate != null && appliedRouteBlocked == blocked && appliedRevision == policyRevision && !installScheduled;
    }

    /** FctID 19/20/21/22/46 ownership follows actual CarPlay RGI, not the
     * session-long native-RG gate. The wrapper is already installed on
     * NavigationJobs. Tightening is immediate; release/replay is queued. */
    public static synchronized void setCurrentPositionInfoBlocked(boolean blocked) {
        setCurrentPositionInfoBlocked(blocked,false);
    }
    public static synchronized void setCurrentPositionInfoBlocked(boolean blocked,boolean textOnly) {
        textOnly=blocked && textOnly;
        if (desiredCurrentPositionBlocked != blocked || desiredCurrentPositionTextOnly!=textOnly) ++policyRevision;
        desiredCurrentPositionBlocked = blocked;
        desiredCurrentPositionTextOnly=textOnly;
        if (blocked && gate != null) gate.tightenCurrentPositionInfo(textOnly);
        scheduleInstallLocked();
    }
    public static synchronized boolean isCurrentPositionInfoBlocked(boolean textOnly) {
        return gate!=null && gate.ownsCurrentPosition(textOnly) && appliedRevision==policyRevision && !installScheduled;
    }

    /** Called by the existing resident lifecycle monitor, including after disconnect. */
    public static synchronized void retryPending(boolean monitorServices) {
        if (!monitorServices && !desiredRouteBlocked && !desiredCurrentPositionBlocked
                && (gate == null || (appliedRevision == policyRevision && !gate.hasPendingReplay()))) return;
        long now = System.currentTimeMillis();
        if (lastRetryMs != 0L && now >= lastRetryMs && now - lastRetryMs < 1000L) return;
        lastRetryMs = now;
        appliedRevision = -1;
        scheduleInstallLocked();
    }

    /** Exact stock-service availability edge, after prepareCombiBAPService + stock updateAll.
     * Also wakes CarPlayApp so RgdModule releases/reacquires its paired OSGi reference when the
     * Navigation service is replaced without a phone reconnect. */
    public static synchronized void onCombiBAPServiceChanged(ClusterService cs) {
        CombiBAPServiceNavi current = null;
        try { if (cs != null) current = cs.getCombiBAPListenerCombiService(); }
        catch (Throwable t) { }

        if (cs != clusterService || current != gate) {
            appliedRouteBlocked = false;
            appliedRevision = -1;
        }
        if (current == null) {
            if (cs == clusterService) {
                if (gate != null) gate.retire();
                clusterService = null;
                gate = null;
                appliedRevision = -1;
                installScheduled = false;
                ++installTicket;
                GatedCombiService.publishMapVisibility(false);
            }
            CarPlayApp.onNavigationServiceChanged();
            return;
        }
        if (desiredRouteBlocked || desiredCurrentPositionBlocked) scheduleInstallLocked();
        CarPlayApp.onNavigationServiceChanged();
    }
}
