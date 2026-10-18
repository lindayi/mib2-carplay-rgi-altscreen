/*
 * Stock terminal-mode call-state bridge; adapted from upstream V3.8.
 * Copyright (c) 2026 LuKa (@LuKa_dev)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.luka.carplay.telephony;

import de.audi.app.terminalmode.events.CallStateChangedEvent;
import de.audi.app.terminalmode.events.DefaultEventListener;
import de.audi.atip.utils.generics.GList;

final class TelephonyEventBridge extends DefaultEventListener {
    private final TelephonyModule owner;
    private final int epoch;

    TelephonyEventBridge(TelephonyModule owner, int epoch) {
        this.owner = owner;
        this.epoch = epoch;
    }

    public void updateCallState(GList list) {
        if (!owner.isEpoch(epoch)) return;
        try {
            int n = list == null ? 0 : Math.max(0, Math.min(7, list.size()));
            CallStateChangedEvent[] calls = new CallStateChangedEvent[n];
            for (int i = 0; i < n; i++) {
                Object o = list.get(i);
                if (o instanceof CallStateChangedEvent) calls[i] = (CallStateChangedEvent)o;
            }
            owner.offerCalls(epoch, calls);
        } catch (Throwable t) {
            owner.offerCalls(epoch, new CallStateChangedEvent[0]);
        }
    }
}
