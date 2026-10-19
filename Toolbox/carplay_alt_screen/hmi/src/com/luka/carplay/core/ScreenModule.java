package com.luka.carplay.core;

import com.luka.carplay.cluster.AltScreenCluster;
import com.luka.carplay.cluster.AltScreenPlacement;
import com.luka.carplay.cluster.AltScreenVideo;
import com.luka.carplay.cluster.ClusterLayerController;
import com.luka.carplay.cluster.ClusterStateController;
import com.luka.carplay.framework.Log;
import de.audi.atip.hmi.view.IDisplayManager;
import de.audi.tghu.fwhmi.IDisplayManagerKombiControl;

/** MU1316 feature hooks. ClusterStateController owns the sole context worker. */
public final class ScreenModule implements Module {
    public static final int TERMINAL_CLUSTER = ClusterStateController.TERMINAL_CLUSTER;
    public static final int CTX_CLUSTER = ClusterStateController.CTX_MAP;
    public static final int CTX_CLUSTER_VIDEO = ClusterStateController.CTX_COMPOSITE;
    public static final int CTX_STOCK_CLUSTER = ClusterStateController.CTX_STOCK;
    public static final int VIEWAREA_FULLSCREEN = 0;
    public static final int VIEWAREA_SMALLSCREEN = 1;
    private static volatile boolean platformSupported = true;
    private static volatile boolean videoAllowed = true;
    private static volatile boolean smallScreenViewArea;
    private static volatile ViewAreaModeListener viewAreaModeListener;
    private static volatile InfoModeListener infoModeListener;
    private boolean enabled;

    public interface ViewAreaModeListener { void onViewAreaModeChanged(int mode); }
    public interface InfoModeListener { void onInfoModeToggle(); }

    public static boolean isPlatformSupported(FrameworkRef fw) {
        try {
            return fw != null && fw.framework() != null && fw.framework().getKombiType() != 4;
        } catch (Throwable t) {
            Log.w("Screen", "cluster variant unavailable: " + t);
            return true;
        }
    }

    public static boolean isConnected() {
        return platformSupported && ClusterStateController.isCarPlaySessionActive();
    }
    public static boolean isClusterContextWriterThread() {
        return ClusterStateController.isContextWriterThread();
    }
    public static boolean isNavActive() { return ClusterStateController.isRgiPresentationActive(); }
    public static boolean isAltScreenVideo() { return ClusterStateController.isVideoReady(); }
    public static void setNavActive(boolean active) {
        ClusterStateController.setRgiPresentationActive(active);
    }
    public static void releaseGuidance() { ClusterStateController.deactivateRgiRenderer(); }
    public static void onVcKdkVisibility(boolean visible) {
        ClusterStateController.onKdkVisibility(visible);
    }
    public static int requestRendererBinding() {
        return isConnected() ? ClusterStateController.requestRendererBinding() : 0;
    }
    public static boolean isRendererBindingApplied(int revision) {
        return ClusterStateController.isRendererBindingApplied(revision);
    }
    static int contextFor(boolean connected, boolean video, boolean nav) {
        return ClusterStateController.contextFor(connected, video, nav);
    }
    public static void setVideoAllowed(boolean allowed) {
        videoAllowed = allowed;
        if (!allowed) {
            ClusterStateController.setVideoReady(false);
            AltScreenCluster.setVideoReady(false);
        }
    }

    /** Called only by the controller worker; callbacks never read marker files. */
    public static void pollPlatformState() {
        boolean connected = isConnected();
        OemMapStartupGate.setActive(connected && videoAllowed);
        OemMapStartupGate.tick();
        int connection = com.luka.carplay.bus.CarplayBus.getInstance().connectionGeneration();
        AltScreenCluster.setStartupAllowed(connection, OemMapStartupGate.isPublished(connection));
        AltScreenCluster.publishHmiState(connected && videoAllowed);
        AltScreenCluster.flushLayout();
        AltScreenPlacement.setPhoneProfile(AltScreenCluster.phoneProfileForSession());
        boolean ready = connected && videoAllowed && AltScreenVideo.isReady();
        boolean changed = ready != ClusterStateController.isVideoReady();
        ClusterStateController.setVideoReady(ready);
        AltScreenCluster.setVideoReady(ready);
        if (changed && ready) AltScreenPlacement.onVideoReady();
        AltScreenPlacement.setActive(ready);
        if (ready) AltScreenPlacement.poll();
        AltScreenCluster.flushLayout();
    }

    public static boolean isSmallScreenViewArea() { return smallScreenViewArea; }
    public static void setViewAreaModeListener(ViewAreaModeListener listener) {
        viewAreaModeListener = listener;
    }
    public static void clearViewAreaModeListener(ViewAreaModeListener listener) {
        if (viewAreaModeListener == listener) viewAreaModeListener = null;
    }
    public static void setViewAreaMode(int mode) {
        boolean small = mode == VIEWAREA_SMALLSCREEN;
        if (smallScreenViewArea == small) return;
        smallScreenViewArea = small;
        ViewAreaModeListener listener = viewAreaModeListener;
        if (listener != null) {
            try { listener.onViewAreaModeChanged(small ? VIEWAREA_SMALLSCREEN : VIEWAREA_FULLSCREEN); }
            catch (Throwable t) { Log.w("Screen", "view-area listener failed: " + t); }
        }
    }
    public static void setInfoModeListener(InfoModeListener listener) { infoModeListener = listener; }
    public static void clearInfoModeListener(InfoModeListener listener) {
        if (infoModeListener == listener) infoModeListener = null;
    }
    public static void onSteeringWheelOkPressed() {
        InfoModeListener listener = infoModeListener;
        if (isConnected() && listener != null) listener.onInfoModeToggle();
    }

    public String name() { return "screen"; }
    public boolean start(FrameworkRef fw) {
        if (fw == null || !fw.isReady()) return false;
        platformSupported = isPlatformSupported(fw);
        if (!platformSupported) {
            Log.w("Screen", "disabled: unsupported G24 cluster");
            return true;
        }
        IDisplayManager dm;
        try {
            if (fw.framework().getHMIService() == null) return false;
            dm = fw.framework().getHMIService().getDisplayManager();
        } catch (Throwable t) {
            Log.w("Screen", "display manager lookup failed: " + t);
            return false;
        }
        if (dm == null) return false;
        if (dm instanceof IDisplayManagerKombiControl) {
            ClusterLayerController.bind((IDisplayManagerKombiControl)dm, TERMINAL_CLUSTER);
        }
        enabled = true;
        ClusterStateController.start(fw.framework());
        if (!ClusterStateController.isRunning()) return false;
        ClusterStateController.setCarPlaySessionActive(true);
        AltScreenCluster.start();
        return true;
    }
    public void stop() {
        if (!enabled) return;
        enabled = false;
        ClusterStateController.setCarPlaySessionActive(false);
        OemMapStartupGate.setActive(false);
        AltScreenCluster.stop();
    }
}
