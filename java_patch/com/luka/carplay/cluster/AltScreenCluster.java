/*
 * AltScreenCluster — commands for the CarPlay instrument-cluster display that the
 * AltScreen mirror shows on the VC (see AltScreenVideo).
 *
 *   Steering-wheel roller -> CMD_ALT_ZOOM: while the CarPlay video is on the cluster,
 *   each stock MapScale step also zooms the iPhone's cluster map (the hook sends the
 *   AirPlay "changeMapZoomLevel" command).  Stock still zooms its own hidden map.
 *
 *   Cluster UI context -> CMD_ALT_UICTX: if /mnt/app/root/hooks/cluster_ui.url holds a
 *   maps:/car/instrumentcluster URL, it is sent as "showUI" when video comes up and
 *   when a route settles/changes or the VC changes map size. The screen worker performs I/O;
 *   route callbacks only publish intent, independently of the BAP/renderer mode.
 */
package com.luka.carplay.cluster;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.framework.Log;
import com.luka.carplay.settings.Preferences;
import com.luka.carplay.settings.Setting;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public final class AltScreenCluster {

    private static final String TAG = "AltCluster";
    public static final String UI_URL_FILE = "/mnt/app/root/hooks/cluster_ui.url";
    private static final String DEFAULT_UI_URL =
        "maps:/car/instrumentcluster/map?maneuverLayout=topaligned";
    private static final String URL_PREFIX = "maps:";
    private static final int MAX_URL = 400;

    /* Host tests point this at a scratch file. */
    private static String uiUrlPath = UI_URL_FILE;

    private static final Object LOCK = new Object();
    private static final int VIDEO = 1, ROUTE = 2, VIEW = 4;
    private static boolean enabled, video;
    private static int connection = -1;
    private static int pending;
    private static int lifecycle;
    private static long revision;
    private static int presentation = -1;
    private static long viewChangedAt;
    private static int routeState = -1, sourceSupportsRg = -1;
    private static long routeGeneration = -1L;
    private static String sourceName;
    /* Keep the applied layout across module restarts; saved changes require a receiver reconnect. */
    private static int urlConnection = -1;
    private static String sessionUrl;
    private static CarplayBus.Observer routeObserver;

    private AltScreenCluster() {}

    public static void start() {
        synchronized (LOCK) {
            if (routeObserver != null)
                CarplayBus.getInstance().removeObserver(CarplayBus.EVT_RGD_UPDATE, routeObserver);
            final int run = ++lifecycle;
            enabled = true;
            video = false;
            connection = -1;
            presentation = -1;
            pending = 0;
            revision++;
            resetRoute();
            routeObserver = new CarplayBus.Observer() {
                public void onFrame(int generation, int type, int flags, byte[] payload, int len) {
                    onRouteFrame(run, generation, CarplayBus.parseText(payload, len));
                }
            };
            CarplayBus.getInstance().addObserver(CarplayBus.EVT_RGD_UPDATE, routeObserver);
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            enabled = false;
            video = false;
            pending = 0;
            lifecycle++;
            revision++;
            resetRoute();
            if (routeObserver != null)
                CarplayBus.getInstance().removeObserver(CarplayBus.EVT_RGD_UPDATE, routeObserver);
            routeObserver = null;
        }
    }

    private static void resetRoute() {
        routeState = sourceSupportsRg = -1;
        routeGeneration = -1L;
        sourceName = null;
    }

    private static void useConnection(int generation) {
        if (connection == generation) return;
        connection = generation;
        presentation = -1;
        resetRoute();
        pending = video && generation >= 0 ? VIDEO : 0;
        revision++;
    }

    private static boolean settled(int state) { return state == 1 || state == 6; }

    private static void onRouteFrame(int run, int generation, CarplayBus.Data data) {
        synchronized (LOCK) {
            if (!enabled || run != lifecycle
                    || generation != CarplayBus.getInstance().connectionGeneration()) return;
            useConnection(generation);
            if (data.has("disconnect_reason")) {
                resetRoute();
                pending = 0;
                revision++;
                return;
            }
            boolean wasSettled = settled(routeState);
            long oldGeneration = routeGeneration;
            String oldSource = sourceName;
            if (data.has("route_generation")) {
                long next = data.num64("route_generation", -1L);
                if (next >= 0L) routeGeneration = next;
                else Log.w(TAG, "ignoring invalid route generation");
            }
            if (data.has("route_state")) routeState = data.num("route_state", -1);
            if (data.has("source_supports_rg")) sourceSupportsRg = data.num("source_supports_rg", -1);
            if (data.has("source_name")) sourceName = data.str("source_name");
            if (sourceSupportsRg == 0) routeState = 0;
            if (!settled(routeState)) {
                if ((pending & ROUTE) != 0) { pending &= ~ROUTE; revision++; }
                return;
            }
            boolean newSource = oldSource != null && sourceName != null && !oldSource.equals(sourceName);
            if (!wasSettled || routeGeneration != oldGeneration || newSource) {
                pending |= ROUTE;
                revision++;
            }
        }
    }

    /** Lightweight publication under ScreenModule's context lock; no files or socket writes. */
    public static void setVideoReady(boolean ready) {
        synchronized (LOCK) {
            if (!enabled || video == ready) return;
            video = ready;
            pending = ready ? pending | VIDEO : 0;
            revision++;
        }
    }

    /** Actual Fct54 size edges only; duplicate status and drawer changes do not request layout. */
    public static void onPresentation(boolean large) {
        synchronized (LOCK) {
            if (!enabled) return;
            useConnection(CarplayBus.getInstance().connectionGeneration());
            int next = large ? 1 : 0;
            if (presentation == next) return;
            boolean edge = presentation != -1;
            presentation = next;
            if (!edge || !video || connection < 0) return;
            pending |= VIEW;
            viewChangedAt = System.currentTimeMillis();
            revision++;
        }
    }

    /** Only the persistent screen worker calls this. No successful request is retried on a timer. */
    public static void flushLayout() {
        int generation;
        long request;
        String url;
        boolean load;
        synchronized (LOCK) {
            if (!enabled) return;
            generation = CarplayBus.getInstance().connectionGeneration();
            useConnection(generation);
            if (!video || generation < 0 || pending == 0) return;
            long now = System.currentTimeMillis();
            if ((pending & VIEW) != 0 && now >= viewChangedAt && now - viewChangedAt < 350L) return;
            request = revision;
            load = urlConnection != generation;
            url = sessionUrl;
        }
        if (load) url = readUiUrl();
        byte[] bytes = null;
        try { if (url != null) bytes = url.getBytes("UTF-8"); }
        catch (IOException e) { Log.e(TAG, "cannot encode cluster layout", e); }
        synchronized (LOCK) {
            if (!enabled || !video || revision != request
                    || generation != CarplayBus.getInstance().connectionGeneration()) return;
            if (load) { sessionUrl = url; urlConnection = generation; }
            if (bytes == null) {
                pending = 0;
                Log.w(TAG, "cluster layout unavailable; reconnect after repairing preferences");
                return;
            }
            int reasons = pending;
            String reason = (reasons & VIDEO) != 0 ? "video" : "";
            if ((reasons & ROUTE) != 0) reason += (reason.length() == 0 ? "" : "+") + "route";
            if ((reasons & VIEW) != 0) reason += (reason.length() == 0 ? "" : "+") + "view";
            boolean sent = CarplayBus.getInstance().sendBinary(CarplayBus.CMD_ALT_UICTX, bytes, generation);
            if (sent) pending = 0;
            Log.i(TAG, "cluster showUI " + (sent ? "queued" : "not queued")
                + " connection=" + generation
                + " reason=" + reason
                + " route_generation=" + routeGeneration + " route_state=" + routeState + " url=" + url);
        }
    }

    /** Reuse only the latched URL; enqueueing performs no socket I/O or settings reads. */
    public static void reapplyLayout() throws IOException {
        synchronized (LOCK) {
            if (!enabled || !video) throw new IOException("CarPlay cluster video is not active");
            int generation = CarplayBus.getInstance().connectionGeneration();
            if (generation < 0) throw new IOException("Receiver is not connected");
            if (urlConnection != generation || sessionUrl == null)
                throw new IOException("Session layout is not ready; reconnect if preferences were invalid");
            if (!CarplayBus.getInstance().sendBinary(CarplayBus.CMD_ALT_UICTX,
                    sessionUrl.getBytes("UTF-8"), generation))
                throw new IOException("Receiver changed; layout was not queued");
            pending = 0;
            revision++;
            Log.i(TAG, "cluster showUI queued connection=" + generation + " reason=manual url=" + sessionUrl);
        }
    }

    /** Stock CombiBAPListener.setMapScale steps (positive = zoom out). */
    public static void onMapScaleSteps(int steps) {
        if (steps == 0 || !ScreenModule.isAltScreenVideo()) return;
        Preferences.Snapshot preferences=Preferences.get().snapshot();
        if(!preferences.on(Setting.ZOOM))return;
        if(preferences.get(Setting.ZOOM_SPEED)==1) {
            if(steps>63)steps=127;else if(steps<-63)steps=-127;else steps*=2;
        }
        if (steps > 127) steps = 127;
        if (steps < -127) steps = -127;
        boolean sent = CarplayBus.getInstance().sendBinary(CarplayBus.CMD_ALT_ZOOM,
            new byte[] { (byte)steps });
        Log.i(TAG, "roller steps=" + steps + " -> cluster zoom " + (steps > 0 ? "out" : "in")
            + (sent ? "" : " (bus down)"));
    }

    static String readUiUrl() {
        FileInputStream in = null;
        try {
            if(Preferences.get().exists()) {
                if(Preferences.get().error().length()!=0)return null;
                switch(Preferences.get().snapshot().get(Setting.LAYOUT)) {
                    case 1:return "maps:/car/instrumentcluster/map?maneuverLayout=rightaligned";
                    case 2:return "maps:/car/instrumentcluster/map?showETA=no";
                    case 3:return "maps:/car/instrumentcluster/map";
                    default:return DEFAULT_UI_URL;
                }
            }
            File f = new File(uiUrlPath);
            if (!f.exists()) return DEFAULT_UI_URL;
            long length = f.length();
            if (length <= 0 || length > MAX_URL) {
                Log.w(TAG, "ignoring " + uiUrlPath + ": invalid layout length");
                return null;
            }
            byte[] buf = new byte[(int)length];
            in = new FileInputStream(f);
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) throw new IOException("Layout file truncated during read");
                n += r;
            }
            if (in.read() != -1) throw new IOException("Layout file grew during read");
            String url = new String(buf, 0, n, "UTF-8").trim();
            if (!url.startsWith(URL_PREFIX)) {
                Log.w(TAG, "ignoring " + uiUrlPath + ": not a maps: URL");
                return null;
            }
            return url;
        } catch (IOException e) {
            Log.w(TAG, "cannot read cluster layout: " + e);
            return null;
        } catch (SecurityException e) {
            Log.w(TAG, "cluster layout access denied: " + e);
            return null;
        } finally {
            if (in != null) try { in.close(); }
            catch (IOException e) { Log.w(TAG, "cannot close cluster layout: " + e); }
        }
    }
}
