/*
 * AltScreenPlacement — where the AltScreen CarPlay video plane (displayable 3) sits on the VC.
 *
 * Stock CombiMapController.positionMap() puts its own map planes 33/58 at the layout's map
 * origin (108/109) and, in the VC's small view, adds the layout's small-stage offset (80/81):
 * (0,0) on Classic (B9), (-476,0) on Sport (B9Sport), where the single large dial covers the
 * middle of the cluster and the map is only visible on the left.  Displayable 3 is not a stock
 * map plane, so without this the video keeps its place and Sport shows the left half of it,
 * with the vehicle marker behind the dial.  AltScreen V3 applies the same -476 inside its
 * mirror renderer; here the plane itself moves, exactly as stock moves its map.
 *
 * On top of that /mnt/app/root/hooks/cluster_shift.cfg (GEM "Cluster map shift") may add a
 * horizontal offset per view:  full_dx=<px>  small_dx=<px>  (|dx| <= 720).  GEM only writes
 * small_dx=-476 ("Sport test"): on Classic it reproduces what Sport does, so the mechanism can
 * be checked on a Classic-only car.  (Amap / Baidu centring is the hook's view area instead,
 * hook/altcluster/alt_viewarea.c.)
 *
 * Displayable 3 is only touched once a non-zero offset is wanted; after that it is always
 * written (back to the map origin when the offset returns to zero).
 */
package com.luka.carplay.cluster;

import com.luka.carplay.framework.Log;
import de.audi.atip.hmi.view.IDisplayManager;

import java.io.File;
import java.io.FileInputStream;

public final class AltScreenPlacement {

    private static final String TAG = "AltPlace";
    public static final String SHIFT_FILE = "/mnt/app/root/hooks/cluster_shift.cfg";
    static final int VIDEO = 3;
    static final int MAX_DX = 720;

    private static final Object LOCK = new Object();
    /* Host tests point this at a scratch file. */
    private static String shiftPath = SHIFT_FILE;

    private static IDisplayManager dm;
    private static int terminal;
    private static boolean haveMap;
    private static int mapX, mapY, stageDx, stageDy;
    private static boolean small;
    private static String layoutName = "?";

    private static String cfgText;
    private static boolean configLoaded;
    private static int fullDx, smallDx;

    private static boolean touched;
    private static boolean active;
    private static int lastX, lastY;

    private AltScreenPlacement() {}

    /** CombiMapController.positionMap(): the stock map placement inputs, on every view change. */
    public static void onMapPlacement(IDisplayManager displayManager, int term, String layout,
                                      int originX, int originY, int smallStageDx, int smallStageDy,
                                      boolean smallStage) {
        synchronized (LOCK) {
            dm = displayManager;
            terminal = term;
            layoutName = layout != null ? layout : "?";
            mapX = originX;
            mapY = originY;
            stageDx = smallStageDx;
            stageDy = smallStageDy;
            small = smallStage;
            haveMap = true;
            applyLocked("view " + (smallStage ? "small" : "full"));
        }
    }

    /** ScreenModule switch worker (~4 Hz while connected): pick up GEM changes live. */
    public static void poll() {
        synchronized (LOCK) {
            if (!active) return;
            if (loadShiftLocked()) applyLocked("shift config");
        }
    }

    public static void setActive(boolean value) {
        synchronized (LOCK) {
            active = value;
            if (!active && touched && dm != null) {
                try {
                    dm.setPosition(VIDEO, terminal, mapX, mapY);
                    touched = false;
                } catch (Throwable t) {
                    Log.w(TAG, "release video position failed: " + t);
                }
            }
        }
    }

    /** The video (re)appeared: its window may be new, so write the position again. */
    public static void onVideoReady() {
        synchronized (LOCK) {
            active = true;
            loadShiftLocked();
            touched = false;     /* new window: rewrite if an offset is in effect */
            applyLocked("video ready");
        }
    }

    /** Target position of displayable 3 for the current view, or null before stock placed its map. */
    static int[] target() {
        synchronized (LOCK) {
            if (!haveMap) return null;
            int x = mapX + (small ? stageDx + smallDx : fullDx);
            int y = mapY + (small ? stageDy : 0);
            return new int[] { x, y };
        }
    }

    private static void applyLocked(String why) {
        if (!active || !haveMap || dm == null) return;
        int x = mapX + (small ? stageDx + smallDx : fullDx);
        int y = mapY + (small ? stageDy : 0);
        boolean atOrigin = x == mapX && y == mapY;
        if (!touched && atOrigin) return;            /* never moved: leave AltScreen's plane alone */
        if (touched && x == lastX && y == lastY) return;
        try {
            dm.setPosition(VIDEO, terminal, x, y);
        } catch (Throwable t) {
            Log.w(TAG, "setPosition(3) failed: " + t);
            return;
        }
        touched = true;
        lastX = x;
        lastY = y;
        Log.i(TAG, "video plane -> (" + x + "," + y + ") " + why + " layout=" + layoutName
            + " view=" + (small ? "small" : "full") + " origin=(" + mapX + "," + mapY + ")"
            + " stage=(" + stageDx + "," + stageDy + ") shift full=" + fullDx + " small=" + smallDx);
    }

    /** Worker-only bounded read; QNX timestamps cannot distinguish every same-size save. */
    private static boolean loadShiftLocked() {
        File f = new File(shiftPath);
        String text = f.exists() ? read(f) : null;
        if (configLoaded && (text == null ? cfgText == null : text.equals(cfgText))) return false;
        configLoaded = true;
        cfgText = text;
        int full = 0, sm = 0;
        if (text != null) {
            full = value(text, "full_dx");
            sm = value(text, "small_dx");
        }
        boolean changed = full != fullDx || sm != smallDx;
        fullDx = full;
        smallDx = sm;
        if (changed) Log.i(TAG, "shift config full_dx=" + full + " small_dx=" + sm);
        return changed;
    }

    static int value(String text, String key) {
        int at = 0;
        while (at < text.length()) {
            int end = text.indexOf('\n', at);
            if (end < 0) end = text.length();
            String line = text.substring(at, end).trim();
            at = end + 1;
            if (!line.startsWith(key + "=")) continue;
            try {
                int v = Integer.parseInt(line.substring(key.length() + 1).trim());
                if (v < -MAX_DX || v > MAX_DX) {
                    Log.w(TAG, "out-of-range shift " + key + "; using zero");
                    return 0;
                }
                return v;
            } catch (NumberFormatException e) {
                Log.w(TAG, "invalid shift " + key + "; using zero");
                return 0;
            }
        }
        return 0;
    }

    private static String read(File f) {
        if (f.length() > 256) {
            Log.w(TAG, "shift config exceeds 256 bytes; using zero");
            return null;
        }
        FileInputStream in = null;
        try {
            byte[] buf = new byte[(int)f.length()];
            in = new FileInputStream(f);
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, "US-ASCII");
        } catch (Throwable t) {
            Log.w(TAG, "cannot read shift config; using zero: " + t);
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable t) { }
        }
    }

    /* Host tests only. */
    static void resetForTest(String path) {
        synchronized (LOCK) {
            shiftPath = path;
            dm = null; haveMap = false; touched = false; small = false; active = false;
            cfgText = null; configLoaded = false; fullDx = 0; smallDx = 0;
        }
    }
}
