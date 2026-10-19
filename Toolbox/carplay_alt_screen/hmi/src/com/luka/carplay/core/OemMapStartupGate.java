package com.luka.carplay.core;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.framework.Log;
import de.audi.tghu.navi.app.Navigation;

/** Adapted from yuedizhibo V3.8's GPL OEM-map startup gate.
 * Probe on NavigationJobs; only the existing screen worker queues readiness. */
public final class OemMapStartupGate {
    private static final Object LOCK = new Object();
    private static final byte[] READY = new byte[] { 1 };
    private static boolean active, ready, pending, failureLogged;
    private static int generation, sentConnection = -1;

    private OemMapStartupGate() { }

    static void setActive(boolean value) {
        synchronized (LOCK) {
            if (active == value) return;
            active = value;
            generation++;
            pending = false;
            sentConnection = -1;
        }
    }

    /** Called only by ScreenModule's persistent worker, before video readiness. */
    static void tick() {
        final int token;
        boolean probe;
        synchronized (LOCK) {
            if (!active) return;
            token = generation;
            probe = !ready && !pending;
            if (probe) pending = true;
        }
        if (probe) {
            try {
                final Navigation navigation = Navigation.getInstance();
                if (navigation == null || navigation.getDispatcher() == null) {
                    completeProbe(token, false);
                    return;
                }
                navigation.getDispatcher().execute(new Runnable() {
                    public void run() {
                        synchronized (LOCK) {
                            if (!active || token != generation) return;
                        }
                        try {
                            completeProbe(token, navigation.getMapManager() != null
                                && navigation.getMapManager().getMapKombi() != null
                                && navigation.getMapManager().getMapKombi().isInitialized());
                        } catch (Throwable error) {
                            probeFailed(token, error);
                        }
                    }
                });
            } catch (Throwable error) {
                probeFailed(token, error);
            }
        }
        CarplayBus bus = CarplayBus.getInstance();
        int connection = bus.connectionGeneration();
        synchronized (LOCK) {
            if (!active || token != generation || !ready || !bus.isConnected()
                    || connection == sentConnection) return;
            if (bus.sendBinary(CarplayBus.CMD_OEM_MAP_READY, READY, connection)) {
                sentConnection = connection;
                Log.i("OemMapGate", "READY queued connection=" + connection);
            }
        }
    }

    static void completeProbe(int token, boolean initialized) {
        synchronized (LOCK) {
            if (!active || token != generation) return;
            pending = false;
            if (initialized && !ready) {
                ready = true;
                failureLogged = false;
                Log.i("OemMapGate", "MapKombi initialized; boot readiness latched");
            }
        }
    }

    static boolean isPublished(int connection) {
        synchronized (LOCK) {
            return active && ready && connection >= 0 && sentConnection == connection;
        }
    }

    private static void probeFailed(int token, Throwable error) {
        synchronized (LOCK) {
            if (!active || token != generation) return;
            pending = false;
            if (failureLogged) return;
            failureLogged = true;
        }
        Log.e("OemMapGate", "OEM map probe failed; cluster request remains deferred", error);
    }
}
