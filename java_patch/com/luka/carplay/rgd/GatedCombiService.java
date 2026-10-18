/*
 * GatedCombiService - Wrapper around CombiBAPServiceNavi that gates
 * native route-guidance updates during CarPlay route guidance.
 *
 * When blockRouteGuidance=true, native route-guidance related calls from
 * CombiBAPListener are cached so they don't overwrite BAPBridge, then replayed
 * on NavigationJobs when ownership returns to Audi.
 * This keeps BAPBridge as the single source for FctIDs
 * 17/18/19/20/21/22/23/24/39/46/49/55.
 *
 * Non-route-guidance methods always delegate.
 *
 *
 */
package com.luka.carplay.rgd;

import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import de.audi.atip.interapp.combi.bap.audio.data.CombiBAPTMCInfoMessage;
import de.audi.atip.interapp.combi.bap.navi.data.CombiBAPDestinationInfo;
import de.audi.atip.interapp.combi.bap.navi.data.CombiBAPDestinationListEntry;
import de.audi.atip.interapp.combi.bap.navi.data.CombiBAPNaviDestination;
import de.audi.atip.interapp.combi.bap.navi.data.CombiBAPNaviLaneGuidanceData;
import de.audi.atip.interapp.combi.bap.navi.data.CombiBAPNaviManeuverDescriptor;
import de.audi.atip.interapp.combi.bap.navi.data.CombiBAPSemiDynamicRouteInfo;
import de.audi.atip.interapp.combi.bap.navi.data.EtcStatus;

public class GatedCombiService implements CombiBAPServiceNavi {
    final CombiBAPServiceNavi real;
    volatile boolean blockRouteGuidance;
    /* FctIDs 19/20/21/22/46 have different ownership from the rest of route guidance:
     * outside CarPlay RGI the stock navigator may keep driving the current/next-road
     * text, the lower bar,
     * travel information and destination menu; maneuver/lane records remain
     * session-gated. */
    volatile boolean blockCurrentPositionInfo;
    private boolean currentPositionTextOnly;

    public GatedCombiService(CombiBAPServiceNavi r) { this.real = r; }

    private final Object[][] latest = new Object[12][];
    private final boolean[] replayPending = new boolean[12];
    private volatile boolean retired;
    private long lastReplayWarning;

    private boolean positionField(int i) { return i == 3 || i >= 8; }
    private boolean fieldBlocked(int i) {
        return positionField(i)?blockCurrentPositionInfo && (!currentPositionTextOnly || i==3 || i==8)
            :blockRouteGuidance;
    }

    private void markReplay(boolean position) {
        for (int i = 0; i < latest.length; i++)
            if (positionField(i) == position && latest[i] != null) replayPending[i] = true;
    }

    public synchronized boolean hasPendingReplay() {
        for (int i = 0; i < latest.length; i++)
            if (replayPending[i] && !fieldBlocked(i))
                return true;
        return false;
    }

    /** Retired service snapshots must never be replayed into a replacement. */
    public synchronized void retire() {
        retired = true;
        for (int i = 0; i < latest.length; i++) {
            latest[i] = null;
            replayPending[i] = false;
        }
    }

    private void replay(boolean position) {
        if (retired) return;
        for (int i = 0; i < latest.length; i++) {
            if (!replayPending[i] || latest[i] == null || positionField(i) != position || fieldBlocked(i)) continue;
            Object[] v = latest[i];
            try {
                switch (i) {
                case 0: real.updateRGStatus(((Integer)v[0]).intValue()); break;
                case 1: real.updateActiveRGType(((Integer)v[0]).intValue()); break;
                case 2: real.updateDistanceToNextManeuver(((Integer)v[0]).intValue(), ((Integer)v[1]).intValue(), ((Boolean)v[2]).booleanValue(), ((Integer)v[3]).intValue()); break;
                case 3: real.updateCurrentPositionInfo((String)v[0]); break;
                case 4: real.updateManeuverDescriptor((CombiBAPNaviManeuverDescriptor[])v[0]); break;
                case 5: real.updateLaneGuidance(((Boolean)v[0]).booleanValue(), (CombiBAPNaviLaneGuidanceData[])v[1]); break;
                case 6: real.updateExitView(((Integer)v[0]).intValue(), ((Integer)v[1]).intValue()); break;
                case 7: real.updateManeuverState(((Integer)v[0]).intValue()); break;
                case 8: real.updateTurnToInfo((String)v[0], (String)v[1]); break;
                case 9: real.updateDistanceToDestination(((Integer)v[0]).intValue(), ((Integer)v[1]).intValue(), ((Boolean)v[2]).booleanValue()); break;
                case 10: real.updateTimeToDestination(((Integer)v[0]).intValue(), ((Integer)v[1]).intValue(), ((Long)v[2]).longValue()); break;
                case 11: real.updateDestinationInfo((CombiBAPDestinationInfo)v[0]); break;
                }
                replayPending[i] = false;
            } catch (Throwable t) {
                long now = System.currentTimeMillis();
                if (lastReplayWarning == 0L || now < lastReplayWarning || now - lastReplayWarning >= 10000L) {
                    lastReplayWarning = now;
                    logFailure("OEM replay failed", t);
                }
            }
        }
    }

    /** Route-data ownership. BAPBridge is the only writer for the maneuver FctIDs while CarPlay RGI
     * is active.  Scale and altitude are NOT gated on this branch: the cluster shows the stock native
     * map, so its native scale/altitude readouts in the lower bar stay visible. */
    public synchronized void setRouteGuidanceBlocked(boolean active) {
        if (blockRouteGuidance && !active) markReplay(false);
        blockRouteGuidance = active;
        if (!active) replay(false);
    }

    public synchronized void setCurrentPositionInfoBlocked(boolean blocked) {
        setCurrentPositionInfoBlocked(blocked,false);
    }
    public synchronized void setCurrentPositionInfoBlocked(boolean blocked,boolean textOnly) {
        for(int i=0;i<latest.length;i++) {
            if(positionField(i) && latest[i]!=null && fieldBlocked(i)
                    && !(blocked && (!textOnly || i==3 || i==8)))replayPending[i]=true;
        }
        blockCurrentPositionInfo=blocked;currentPositionTextOnly=blocked && textOnly;
        replay(true);
    }
    /** Tightening from workers may not replay a released field off NavigationJobs. */
    public synchronized void tightenCurrentPositionInfo(boolean textOnly) {
        if(!blockCurrentPositionInfo){blockCurrentPositionInfo=true;currentPositionTextOnly=textOnly;}
        else if(!textOnly)currentPositionTextOnly=false;
    }
    public synchronized boolean ownsCurrentPosition(boolean textOnly) {
        return !retired && blockCurrentPositionInfo && currentPositionTextOnly==textOnly;
    }

    /* GATED ROUTE-GUIDANCE METHODS */
    public synchronized void updateRGStatus(int a) {
        if (retired) return;
        latest[0] = new Object[]{new Integer(a)};
        if (!blockRouteGuidance) { real.updateRGStatus(a); replayPending[0] = false; }
    }

    public synchronized void updateActiveRGType(int a) {
        if (retired) return;
        latest[1] = new Object[]{new Integer(a)};
        if (!blockRouteGuidance) { real.updateActiveRGType(a); replayPending[1] = false; }
    }

    public synchronized void updateDistanceToNextManeuver(int a, int b, boolean c, int d) {
        if (retired) return;
        latest[2] = new Object[]{new Integer(a), new Integer(b), c ? Boolean.TRUE : Boolean.FALSE, new Integer(d)};
        if (!blockRouteGuidance) { real.updateDistanceToNextManeuver(a, b, c, d); replayPending[2] = false; }
    }

    public synchronized void updateCurrentPositionInfo(String s) {
        if (retired) return;
        latest[3] = new Object[]{s};
        if (!fieldBlocked(3)) { real.updateCurrentPositionInfo(s); replayPending[3] = false; }
    }

    public synchronized void updateManeuverDescriptor(CombiBAPNaviManeuverDescriptor[] a) {
        if (retired) return;
        latest[4] = new Object[]{a == null ? null : a.clone()};
        if (!blockRouteGuidance) { real.updateManeuverDescriptor(a); replayPending[4] = false; }
    }

    public synchronized void updateLaneGuidance(boolean a, CombiBAPNaviLaneGuidanceData[] b) {
        if (retired) return;
        latest[5] = new Object[]{a ? Boolean.TRUE : Boolean.FALSE, b == null ? null : b.clone()};
        if (!blockRouteGuidance) { real.updateLaneGuidance(a, b); replayPending[5] = false; }
    }

    public synchronized void updateExitView(int a, int b) {
        if (retired) return;
        latest[6] = new Object[]{new Integer(a), new Integer(b)};
        if (!blockRouteGuidance) { real.updateExitView(a, b); replayPending[6] = false; }
    }

    public synchronized void updateManeuverState(int a) {
        if (retired) return;
        latest[7] = new Object[]{new Integer(a)};
        if (!blockRouteGuidance) { real.updateManeuverState(a); replayPending[7] = false; }
    }

    /* All other methods: pure delegation */
    public void showInitializingScreen() { real.showInitializingScreen(); }
    public void hideInitializingScreen() { real.hideInitializingScreen(); }
    public void updateCompassInfo(int a, int b) { real.updateCompassInfo(a, b); }
    public synchronized void updateTurnToInfo(String a, String b) {
        if (retired) return;
        latest[8] = new Object[]{a, b};
        if (!fieldBlocked(8)) { real.updateTurnToInfo(a, b); replayPending[8] = false; } }
    public synchronized void updateDistanceToDestination(int a, int b, boolean c) {
        if (retired) return;
        latest[9] = new Object[]{new Integer(a), new Integer(b), c ? Boolean.TRUE : Boolean.FALSE};
        if (!fieldBlocked(9)) { real.updateDistanceToDestination(a, b, c); replayPending[9] = false; } }
    public synchronized void updateTimeToDestination(int a, int b, long c) {
        if (retired) return;
        latest[10] = new Object[]{new Integer(a), new Integer(b), new Long(c)};
        if (!fieldBlocked(10)) { real.updateTimeToDestination(a, b, c); replayPending[10] = false; } }
    public void updateTMCInfoMessages(CombiBAPTMCInfoMessage[] a) {
        real.updateTMCInfoMessages(a); }
    public void updateLastDestinationsList(CombiBAPDestinationListEntry[] a) {
        real.updateLastDestinationsList(a); }
    public void updateFavoriteDestinationsList(CombiBAPDestinationListEntry[] a) {
        real.updateFavoriteDestinationsList(a); }
    public void updateHomeAddress(CombiBAPNaviDestination a) { real.updateHomeAddress(a); }
    public void routeGuidanceActDeactResult(int a) { real.routeGuidanceActDeactResult(a); }
    public void repeatLastNavAnnouncementResult(int a) {
        real.repeatLastNavAnnouncementResult(a); }
    public void updateVoiceGuidanceState(int a) { real.updateVoiceGuidanceState(a); }
    public void updateInfoStates(int a) { real.updateInfoStates(a); }
    public void updateTrafficBlockIndication(int a) { real.updateTrafficBlockIndication(a); }
    public void updateMapColor(int a) { real.updateMapColor(a); }
    public void updateMapType(int a, int b) { real.updateMapType(a, b); }
    public void updateSupportedMapTypes(boolean a, int b) { real.updateSupportedMapTypes(a, b); }
    public void updateMapView(int a, int b) { real.updateMapView(a, b); }
    public void updateSupportedMapViews(int a, int b) { real.updateSupportedMapViews(a, b); }
    public void updateMapVisibility(boolean a, boolean b) {
        if (retired) return;
        publishMapVisibility(b);
        real.updateMapVisibility(a, b);
    }
    public static void publishMapVisibility(boolean visible) {
        try { com.luka.carplay.cluster.ClusterLayerController.onVcVisibility(visible); }
        catch (Throwable t) { logFailure("visibility forwarding failed", t); }
    }
    public static void publishMapPresentation(boolean large, boolean left, boolean right) {
        try { com.luka.carplay.cluster.ClusterLayerController.onVcPresentation(large); }
        catch (Throwable t) { logFailure("presentation forwarding failed", t); }
        try { com.luka.carplay.settings.VcPanel.presentation(large, left, right); }
        catch (Throwable t) { logFailure("panel presentation failed", t); }
        try { com.luka.carplay.core.SpeedBadge.presentation(large); }
        catch (Throwable t) { logFailure("badge presentation failed", t); }
    }
    private static void logFailure(String message, Throwable t) {
        try { com.luka.carplay.framework.Log.w("StatusGate", message + ": " + t); }
        catch (Throwable ignored) { }
    }
    public void updateMapOrientation(int a) { real.updateMapOrientation(a); }
    public void updateMapScale(int a, boolean b, int c, int d, boolean e) {
        real.updateMapScale(a, b, c, d, e);   /* native map scale — not gated on this branch */
    }
    public synchronized void updateDestinationInfo(CombiBAPDestinationInfo a) {
        if (retired) return;
        latest[11] = new Object[]{a};
        if (!fieldBlocked(11)) { real.updateDestinationInfo(a); replayPending[11] = false; } }
    public void updateAltitude(int a, int b) {
        real.updateAltitude(a, b);            /* native altitude — not gated on this branch */
    }
    public void updateOnlineNavigationState(int a, int b, int c) {
        real.updateOnlineNavigationState(a, b, c); }
    public void updateSemidynamicRouteGuidance(CombiBAPSemiDynamicRouteInfo a) {
        real.updateSemidynamicRouteGuidance(a); }
    public void poiSearchResult(int a, int b) { real.poiSearchResult(a, b); }
    public void updatePOIListSize(int a) { real.updatePOIListSize(a); }
    public void updateFSGSetup(int a, boolean b) { real.updateFSGSetup(a, b); }
    public void updateMapPresentation(boolean large, boolean left, boolean right) {
        if (retired) return;
        // Stock updateAll() replays this state without calling setMapPresentation().
        publishMapPresentation(large,left,right);
        real.updateMapPresentation(large,left,right);
    }
    public void updateEtcStatus(EtcStatus a) { real.updateEtcStatus(a); }
}
