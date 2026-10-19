package com.luka.carplay.core;

import java.lang.reflect.*;
import java.util.*;
import de.audi.app.terminalmode.IContext;
import de.audi.app.terminalmode.device.IDeviceManager;
import de.audi.app.terminalmode.device.IActiveDeviceStateListener;
import de.audi.app.terminalmode.osgi.IServiceManager;
import org.osgi.framework.*;

/** Exact-stock interfaces, fake service endpoints; never packaged for the HU. */
public final class LifecycleContext {
    public volatile boolean deviceAvailable = true, serviceAvailable = true, nullFirst;
    public volatile int acquired, released, rawAcquired, rawReleased, adds, removes;
    public volatile int releaseFailures, removeFailures;
    public volatile java.util.concurrent.CountDownLatch acquisitionEntered, acquisitionRelease;
    public volatile boolean wrappers, identity = true;
    public volatile Object service = new Object();
    public volatile IActiveDeviceStateListener listener;
    public final List<IActiveDeviceStateListener> prior = new ArrayList<>();
    public final ServiceReference ref = proxy(ServiceReference.class, (p,m,a) ->
        m.getName().equals("getProperty") ? (identity ? Integer.valueOf(System.identityHashCode(service)) : null) : null);
    public final ServiceReference empty = proxy(ServiceReference.class, (p,m,a) -> null);
    public final BundleContext raw = proxy(BundleContext.class, (p,m,a) -> {
        if (m.getName().equals("getService")) { rawAcquired++; return service; }
        if (m.getName().equals("ungetService")) { rawReleased++; return true; }
        return null;
    });
    public interface RawManager extends IServiceManager { BundleContext getBundleContext(); }
    public final IServiceManager manager = proxy(RawManager.class, (p,m,a) -> {
        if (m.getName().equals("getBundleContext")) return raw;
        if (m.getName().equals("getServiceReferences"))
            return !serviceAvailable ? new ServiceReference[0] :
                nullFirst ? new ServiceReference[] { null, empty, ref } : new ServiceReference[] { ref };
        if (m.getName().equals("getService")) {
            if (a[0] == empty) return null;
            if (acquisitionEntered != null) {
                acquisitionEntered.countDown();
                if (!acquisitionRelease.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("service acquisition release timed out");
                acquisitionEntered = null;
            }
            acquired++; return wrappers ? new Object() : service;
        }
        if (m.getName().equals("releaseService")) {
            if (releaseFailures > 0) { releaseFailures--; throw new IllegalStateException("release failed"); }
            released++; return null;
        }
        return null;
    });
    public final IDeviceManager devices = proxy(IDeviceManager.class, (p,m,a) -> {
        if (m.getName().equals("addActiveDeviceListener")) {
            adds++; listener = (IActiveDeviceStateListener)a[0]; prior.add(listener); return null;
        }
        if (m.getName().equals("removeActiveDeviceListener")) {
            if (removeFailures > 0) { removeFailures--; throw new IllegalStateException("remove failed"); }
            removes++; if (listener == a[0]) listener = null; return null;
        }
        return null;
    });
    public final IContext context = proxy(IContext.class, (p,m,a) -> {
        if (m.getName().equals("getDeviceManager")) return deviceAvailable ? devices : null;
        if (m.getName().equals("getServiceManager")) return manager;
        return null;
    });
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[] { type }, handler));
    }
}
