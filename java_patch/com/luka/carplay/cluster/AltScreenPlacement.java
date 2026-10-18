/*
 * AltScreenPlacement — where the AltScreen CarPlay video plane (displayable 3) sits on the VC.
 *
 * Stock CombiMapController.positionMap() puts its own map planes 33/58 at the layout's map
 * origin (108/109) and, in the VC's small view, adds the layout's small-stage offset (80/81):
 * (0,0) on Classic (B9), (-476,0) on Sport (B9Sport), where the single large dial covers the
 * middle of the cluster and the map is only visible on the left.  Displayable 3 is not a stock
 * map plane, so without this the video keeps its place and Sport shows the left half of it,
 * with the vehicle marker behind the dial. This is the sole physical-plane writer:
 * the source mirror does not also translate the video. The latched card profile
 * uses upstream's -445 Sport offset; the map profile retains stock's -476.
 * Legacy cluster_shift.cfg overrides are retained on disk but no longer applied.
 *
 * Displayable 3 is only touched once a non-zero offset is wanted; after that it is always
 * written (back to the map origin when the offset returns to zero).
 */
package com.luka.carplay.cluster;

import com.luka.carplay.framework.Log;
import de.audi.atip.hmi.view.IDisplayManager;

import java.io.File;

public final class AltScreenPlacement {

    private static final String TAG = "AltPlace";
    public static final String SHIFT_FILE = "/mnt/app/root/hooks/cluster_shift.cfg";
    static final int VIDEO = 3;

    private static final Object LOCK = new Object();
    /* Host tests point this at a scratch file. */
    private static String shiftPath = SHIFT_FILE;

    private static IDisplayManager dm;
    private static int terminal;
    private static boolean haveMap;
    private static int mapX, mapY, stageDx, stageDy;
    private static boolean small;
    private static String layoutName = "?";

    private static int phoneProfile=-1;
    private static boolean legacyChecked;

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

    /** ScreenModule worker; legacy diagnostics do not run on stock map callbacks. */
    public static void poll() {
        synchronized (LOCK) {
            if (!active) return;
        }
        if(!legacyChecked) {
            legacyChecked=true;
            try {
                if(new File(shiftPath).exists())
                    Log.w(TAG,"LEGACY_SHIFT_IGNORED: source profile owns geometry; retained "+shiftPath);
            } catch(SecurityException e){Log.w(TAG,"Cannot inspect legacy shift: "+e);}
        }
    }

    public static void setPhoneProfile(int profile) {
        if(profile< -1 || profile>1)throw new IllegalArgumentException("Invalid phone profile");
        synchronized(LOCK) {
            if(phoneProfile==profile)return;
            phoneProfile=profile;
            if(profile<0)restoreLocked();
            else applyLocked("receiver profile");
        }
    }

    public static void setActive(boolean value) {
        synchronized (LOCK) {
            active = value;
            if(!active)restoreLocked();
        }
    }
    private static void restoreLocked() {
        if (touched && dm != null) {
            try {
                dm.setPosition(VIDEO, terminal, mapX, mapY);
                touched = false;
            } catch (Throwable t) {
                Log.w(TAG, "release video position failed: " + t);
            }
        }
    }

    /** The video (re)appeared: its window may be new, so write the position again. */
    public static void onVideoReady() {
        synchronized (LOCK) {
            active = true;
            touched = false;     /* new window: rewrite if an offset is in effect */
            applyLocked("video ready");
        }
    }

    /** Target position of displayable 3 for the current view, or null before stock placed its map. */
    static int[] target() {
        synchronized (LOCK) {
            if (!haveMap || phoneProfile<0) return null;
            int x = mapX + (small ? smallStageX() : 0);
            int y = mapY + (small ? stageDy : 0);
            return new int[] { x, y };
        }
    }

    private static void applyLocked(String why) {
        if (!active || !haveMap || dm == null || phoneProfile<0) return;
        int x = mapX + (small ? smallStageX() : 0);
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
            + " stage=(" + stageDx + "," + stageDy + ") profile=" + (phoneProfile==1?"card":"map"));
    }
    private static int smallStageX() {
        return phoneProfile==1 && stageDx==-476 && layoutName.endsWith("LayoutMIB2HighB9Sport")?-445:stageDx;
    }

    /* Host tests only. */
    static void resetForTest(String path) {
        synchronized (LOCK) {
            shiftPath = path;
            dm = null; haveMap = false; touched = false; small = false; active = false;
            phoneProfile=-1;legacyChecked=false;
        }
    }
}
