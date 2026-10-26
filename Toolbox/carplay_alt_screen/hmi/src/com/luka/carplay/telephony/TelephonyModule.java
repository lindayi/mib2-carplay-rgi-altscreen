/*
 * MU1316 lifecycle adapter for LuKa's GPL-3.0 CarPlay telephony.
 * Copyright (c) 2026 LuKa (@LuKa_dev)
 *
 * Callbacks only copy bounded snapshots / enqueue actions. The worker owns
 * event-bus commands and publication; no phone metadata is written to disk.
 */
package com.luka.carplay.telephony;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.CarPlayApp;
import com.luka.carplay.core.FrameworkRef;
import com.luka.carplay.core.Module;
import com.luka.carplay.framework.Log;
import de.audi.app.terminalmode.events.CallStateChangedEvent;
import de.audi.app.terminalmode.events.IEventBus;

public final class TelephonyModule implements Module {
    private static volatile TelephonyModule current;
    private final Object lock = new Object();
    private volatile boolean running;
    private volatile int connection = -1;
    private volatile int generation;
    private IEventBus eventBus;
    private TelephonyEventBridge bridge;
    private CarplayBus.Observer observer;
    private Thread worker;
    private CallStateChangedEvent[] calls;
    private byte[] lists, comm;
    private final Action[] actions = new Action[16];
    private int head, count;

    private static final class Action {
        GatedPhoneService service;
        int action, connection;
        String number;
    }

    public String name() { return "Telephony"; }

    public synchronized boolean start(FrameworkRef fw) {
        if (running) return true;
        if (fw == null || fw.context() == null) return false;
        final IEventBus b = fw.context().getEventBus();
        if (b == null) return false;
        final int epoch;
        synchronized (lock) {
            epoch = ++generation;
            running = true;
            current = this;
            eventBus = b;
            connection = CarplayBus.getInstance().connectionGeneration();
        }
        bridge = new TelephonyEventBridge(this, epoch);
        try {
            if (!b.registerListener(bridge)) { stop(); return false; }
            GatedPhoneService.setCommands(new GatedPhoneService.Commands() {
                public void dial(String number, int receiver) {
                    if (isEpoch(epoch) && isCurrent(receiver)) b.callNumber(number);
                }
            });
            observer = new CarplayBus.Observer() {
                public void onFrame(int receiver, int type, int flags, byte[] payload, int len) {
                    offerFrame(epoch, receiver, type, payload, len);
                }
            };
            CarplayBus.getInstance().addObserver(CarplayBus.EVT_TEL_LISTS, observer);
            CarplayBus.getInstance().addObserver(CarplayBus.EVT_TEL_COMM, observer);
            worker = new Thread(new Runnable() { public void run() { work(epoch); } }, "carplay-telephony");
            worker.setDaemon(true);
            StockPhoneKeyFilter.install(fw.context());
            worker.start();
            return true;
        } catch (Throwable t) {
            Log.w("Telephony", "start failed");
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        IEventBus b;
        TelephonyEventBridge l;
        CarplayBus.Observer o;
        synchronized (lock) {
            running = false;
            ++generation;
            if (current == this) current = null;
            connection = -1;
            b = eventBus; eventBus = null;
            l = bridge; bridge = null;
            o = observer; observer = null;
            clearLocked();
            lock.notifyAll();
        }
        GatedPhoneService.setCommands(null);
        StockPhoneKeyFilter.uninstall();
        CarplayBus bus = CarplayBus.getInstance();
        if (o != null) {
            bus.removeObserver(CarplayBus.EVT_TEL_LISTS, o);
            bus.removeObserver(CarplayBus.EVT_TEL_COMM, o);
        }
        GatedPhoneService g = GatedPhoneService.current();
        if (g != null) g.setSession(null, -1);
        GatedAddressBook.setCarPlay(false);
        if (b != null && l != null) {
            try { b.unregisterListener(l); }
            catch (Throwable t) { Log.w("Telephony", "listener unregister failed; old generation disabled"); }
        }
    }

    private synchronized void stopIfEpoch(int epoch) {
        if (isEpoch(epoch)) stop();
    }

    private synchronized void refreshStockPhoneFilter(int epoch) {
        if (isEpoch(epoch) && GatedPhoneService.dropStockPhoneKey(20)) StockPhoneKeyFilter.ensure();
    }

    static boolean isEnabled() {
        TelephonyModule m = current;
        GatedPhoneService g = GatedPhoneService.current();
        return m != null && m.isCurrent(m.connection) && g != null && g.isTakingOver();
    }

    boolean isCurrent(int receiver) {
        return running && current == this && CarPlayApp.isActive() && receiver >= 0
            && receiver == connection && receiver == CarplayBus.getInstance().connectionGeneration();
    }

    boolean isEpoch(int epoch) {
        return running && generation == epoch && current == this;
    }

    void offerCalls(int epoch, CallStateChangedEvent[] snapshot) {
        synchronized (lock) {
            if (!isEpoch(epoch) || !isCurrent(connection)) return;
            calls = snapshot;
            lock.notifyAll();
        }
    }

    private void offerFrame(int epoch, int receiver, int type, byte[] payload, int len) {
        if (payload == null || len < 0 || len > payload.length || len > 16384) return;
        synchronized (lock) {
            if (!isEpoch(epoch) || !isCurrent(receiver)) return;
            byte[] copy = new byte[len];
            System.arraycopy(payload, 0, copy, 0, len);
            if (type == CarplayBus.EVT_TEL_LISTS) lists = copy;
            else if (type == CarplayBus.EVT_TEL_COMM) comm = copy;
            lock.notifyAll();
        }
    }

    boolean enqueue(GatedPhoneService g, int action, String number, int receiver) {
        synchronized (lock) {
            if (!isCurrent(receiver) || count == actions.length) return false;
            Action a = new Action();
            a.service = g; a.action = action; a.connection = receiver;
            a.number = number != null && number.length() <= 64 ? number : null;
            actions[(head + count) % actions.length] = a;
            count++;
            lock.notifyAll();
            return true;
        }
    }

    private void clearLocked() {
        calls = null; lists = comm = null;
        for (int i = 0; i < actions.length; i++) actions[i] = null;
        head = count = 0;
    }

    private void work(int epoch) {
        int synced = -1;
        try {
            while (isEpoch(epoch)) {
                CallStateChangedEvent[] c;
                byte[] l, m;
                Action a = null;
                int receiver = CarplayBus.getInstance().connectionGeneration();
                synchronized (lock) {
                    if (!isEpoch(epoch)) return;
                    if (receiver != connection) { clearLocked(); connection = receiver; }
                    c = calls; calls = null;
                    l = lists; lists = null;
                    m = comm; comm = null;
                    if (count > 0) {
                        a = actions[head]; actions[head] = null;
                        head = (head + 1) % actions.length; count--;
                    }
                }
                GatedPhoneService g = GatedPhoneService.current();
                if (g != null) {
                    g.refreshSession(this, epoch, receiver);
                    if (isCurrent(receiver)) {
                        if (c != null) g.deliverCalls(this, epoch, receiver, c);
                        if (l != null) g.deliverFrame(this, epoch, receiver, true, l);
                        if (m != null) g.deliverFrame(this, epoch, receiver, false, m);
                    }
                }
                if (a != null && isCurrent(a.connection))
                    a.service.perform(a.action, a.number, this, epoch, a.connection);
                refreshStockPhoneFilter(epoch);
                if (receiver != synced && isEpoch(epoch) && isCurrent(receiver)
                        && CarplayBus.getInstance().sendBinary(CarplayBus.CMD_SYNC_REQ, new byte[0], receiver))
                    synced = receiver;
                synchronized (lock) {
                    if (isEpoch(epoch) && count == 0 && calls == null && lists == null && comm == null)
                        lock.wait(100L);
                }
            }
        } catch (Throwable t) {
            Log.w("Telephony", "worker failed; restoring stock phone");
            stopIfEpoch(epoch);
        }
    }
}
