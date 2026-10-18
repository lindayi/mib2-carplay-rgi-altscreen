/*
 * Adapted from MHI2Q-CarPlay-AltScreen NativeRouteStateOverlay (GPL-3.0).
 * Copyright (c) 2026 yuedizhibo
 * Keep native DSI updates separate from CarPlay's temporary acceptance flag.
 */
package com.luka.carplay.rgd;

import de.audi.tghu.navi.app.command.DSIResponseContainer;

public final class NativeRouteStateOverlay {
    private static final class State {
        DSIResponseContainer container;
        boolean latest;
        boolean ownWrite;
    }
    private static volatile State current;

    private NativeRouteStateOverlay() { }

    /** Invoked inside the exact stock setter's container monitor by the build weave.
     * Do not acquire the class monitor here: apply locks class then container. */
    public static boolean nativeWrite(Object container, boolean value) {
        State state = current;
        if (state == null || state.container != container || state.ownWrite) return value;
        state.latest = value;
        return true;
    }

    public static synchronized void apply(DSIResponseContainer container, boolean active) {
        if (!active) {
            releaseCurrent();
            return;
        }
        if (container == null) return;
        State previous = current;
        if (previous != null && previous.container != container) releaseCurrent();
        synchronized (container) {
            State state = current;
            if (state == null) {
                state = new State();
                state.container = container;
                state.latest = container.isRgActive();
                current = state;
            }
            state.ownWrite = true;
            try { container.setRgActive(true); }
            finally { state.ownWrite = false; }
        }
    }

    public static synchronized void releaseCurrent() {
        State state = current;
        if (state == null) return;
        synchronized (state.container) {
            current = null;
            state.container.setRgActive(state.latest);
        }
    }
}
