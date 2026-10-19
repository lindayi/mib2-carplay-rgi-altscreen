package com.luka.carplay.routeguidance;

import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** NAVSD INITIALIZING/READY must pass straight through the RGI gate. */
public final class GatedCombiServiceInitStateTest {
    private static final class Recorder implements InvocationHandler {
        int showCount;
        int hideCount;

        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("showInitializingScreen".equals(name)) showCount++;
            else if ("hideInitializingScreen".equals(name)) hideCount++;
            return null;
        }

        CombiBAPServiceNavi service() {
            return (CombiBAPServiceNavi)Proxy.newProxyInstance(
                CombiBAPServiceNavi.class.getClassLoader(),
                new Class[]{CombiBAPServiceNavi.class},
                this
            );
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void testStockForwarding() {
        Recorder recorder = new Recorder();
        GatedCombiService gate = new GatedCombiService(recorder.service());
        gate.showInitializingScreen();
        gate.hideInitializingScreen();
        require(recorder.showCount == 1, "stock show must pass before takeover");
        require(recorder.hideCount == 1, "stock hide must pass before takeover");
    }

    // Dropped from reference: testDeferredInitializingRestored, testDeferredReadyRestored,
    // testIdempotentTakeoverAndNoEdgeRelease. They cover setAltScreenTakeover (cold-boot NAVSD
    // INITIALIZING takeover for the altscreen video plane); the RGI-only fork keeps the stock
    // map and deliberately has no takeover, so INITIALIZING/READY always pass straight through.

    public static void main(String[] args) {
        testStockForwarding();
        System.out.println("GatedCombiService init-state tests: PASS");
    }
}
