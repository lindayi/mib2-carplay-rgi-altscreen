/*
 * Compatibility entry points used by the MU1316 patches and local features.
 * Lifecycle and RGI ownership live in the upstream CarPlayHook.
 */
package com.luka.carplay.core;

import com.luka.carplay.CarPlayHook;
import de.audi.atip.base.IFrameworkAccess;

public final class CarPlayApp {
    public static final String BUILD_ID = "@BUILD_ID@";
    private CarPlayApp() {}
    public static IFrameworkAccess framework() { return CarPlayHook.getFrameworkAccess(); }
    public static boolean isActive() { return CarPlayHook.isActive(); }
    public static boolean isSessionConnected() { return CarPlayHook.isSessionConnected(); }
    public static boolean isGuidanceEnabled() { return CarPlayHook.isGuidanceEnabled(); }
    public static void setFeaturesEnabled(boolean enabled) { CarPlayHook.setFeaturesEnabled(enabled); }
    public static void setGuidanceEnabled(boolean enabled) { CarPlayHook.setGuidanceEnabled(enabled); }
    public static void startTransport(Object context) { CarPlayHook.startTransport(context); }
    public static void onActivate(Object context) { CarPlayHook.onActivate(context); }
    public static void onDeactivate() { CarPlayHook.onDeactivate(); }
    public static void onDeactivateAndWait() { CarPlayHook.onDeactivateAndWait(); }
    public static void onNavigationServiceChanged() { CarPlayHook.onNavigationServiceChanged(); }
}
