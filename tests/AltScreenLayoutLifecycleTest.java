package com.luka.carplay.cluster;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.framework.Log;
import com.luka.carplay.settings.Preferences;
import com.luka.carplay.settings.Setting;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Real bus and layout policy, without BAP or a renderer (also covers map-only mode). */
public final class AltScreenLayoutLifecycleTest {
    private static final String BASE = "maps:/car/instrumentcluster/map";
    private static final String TOP = BASE + "?maneuverLayout=topaligned";
    private static final CarplayBus bus = CarplayBus.getInstance();
    private static volatile CountDownLatch delivered;
    private static int primaryCalls;

    private static void check(boolean value, String why) {
        if (!value) throw new AssertionError(why);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Socket connect() throws Exception {
        int old = bus.connectionGeneration();
        ServerSocket server = (ServerSocket)field(CarplayBus.class, "serverSocket").get(bus);
        Socket peer = new Socket("127.0.0.1", server.getLocalPort());
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (bus.connectionGeneration() == old || bus.connectionGeneration() < 0) {
            check(System.nanoTime() < end, "accept receiver");
            Thread.sleep(1);
        }
        peer.setSoTimeout(2000);
        return peer;
    }
    private static void route(Socket peer, String text) throws Exception {
        delivered = new CountDownLatch(1);
        byte[] payload = text.getBytes("UTF-8");
        DataOutputStream out = new DataOutputStream(peer.getOutputStream());
        out.writeInt(CarplayBus.MAGIC); out.writeInt(1);
        out.writeShort(CarplayBus.EVT_RGD_UPDATE);
        out.writeByte(CarplayBus.FLAG_STICKY | CarplayBus.FLAG_REPLAY); out.writeByte(0);
        out.writeInt(payload.length); out.write(payload); out.flush();
        check(delivered.await(3, TimeUnit.SECONDS), "route callback must return without I/O");
    }
    private static void expect(Socket peer, String url) throws Exception {
        DataInputStream in = new DataInputStream(peer.getInputStream());
        check(in.readInt() == CarplayBus.MAGIC, "command magic"); in.readInt();
        check(in.readUnsignedShort() == CarplayBus.CMD_ALT_UICTX, "layout command type");
        check(in.readUnsignedByte() == CarplayBus.FLAG_BINARY, "layout flags"); in.readByte();
        byte[] bytes = new byte[in.readInt()]; in.readFully(bytes);
        check(url.equals(new String(bytes, "UTF-8")), "selected URL was overridden");
    }
    private static void quiet(Socket peer, String why) throws Exception {
        peer.setSoTimeout(80);
        try {
            int value = peer.getInputStream().read();
            throw new AssertionError(why + " (unexpected byte " + value + ")");
        } catch (SocketTimeoutException expected) {
        } finally { peer.setSoTimeout(2000); }
    }
    private static void flush(Socket peer, String url) throws Exception {
        AltScreenCluster.flushLayout(); expect(peer, url);
    }
    private static void write(Path file, String text) throws Exception {
        Files.write(file, text.getBytes("UTF-8"));
    }

    public static void main(String[] args) throws Exception {
        Log.setLevel(-1);
        Path root = Files.createTempDirectory("layout-lifecycle-");
        Path preference = root.resolve("cluster_ui.url");
        Path nativePreference = root.resolve("preferences");
        Field singleton = field(Preferences.class, "instance");
        Object previousPreferences = singleton.get(null);
        field(AltScreenCluster.class, "uiUrlPath").set(null, preference.toString());
        field(CarplayBus.class, "port").setInt(bus, 0);
        bus.on(CarplayBus.EVT_RGD_UPDATE, new CarplayBus.Listener() {
            public void onFrame(int type, int flags, byte[] payload, int len) {
                primaryCalls++;
                delivered.countDown();
            }
        });
        Socket peer = null;
        try {
            bus.start();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (field(CarplayBus.class, "serverSocket").get(bus) == null) {
                check(System.nanoTime() < end, "start bus"); Thread.sleep(1);
            }
            AltScreenCluster.start();
            AltScreenCluster.setVideoReady(true);
            AltScreenCluster.flushLayout();
            peer = connect();
            flush(peer, TOP);
            route(peer, "route_generation:n:100\nroute_state:n:1\nsource_name:s:Google Maps\n");
            flush(peer, TOP);
            for (int i = 0; i < 50; i++) {
                route(peer, "route_generation:n:100\nroute_state:n:1\nvisible_in_app:n:" + (i % 2)
                    + "\ndist_maneuver_m:n:" + i + "\nm0_ver:n:" + i + "\n");
                AltScreenCluster.setVideoReady(true);
                AltScreenCluster.flushLayout();
            }
            quiet(peer, "distance, maneuver, foreground and duplicate replay must not force layout");
            AltScreenCluster.onPresentation(false);
            AltScreenCluster.flushLayout(); quiet(peer, "initial presentation is only a baseline");
            AltScreenCluster.onPresentation(true);
            AltScreenCluster.onPresentation(false);
            AltScreenCluster.onPresentation(true);
            AltScreenCluster.flushLayout(); quiet(peer, "View burst must settle before layout");
            Thread.sleep(360);
            flush(peer, TOP);
            for(int i=0;i<10;i++){AltScreenCluster.onPresentation(true);AltScreenCluster.flushLayout();}
            quiet(peer, "duplicate presentation or worker polling must not repeat View request");
            AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),true);
            AltScreenCluster.flushLayout();quiet(peer,"initial main-screen activation is only a baseline");
            AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),false);
            AltScreenCluster.flushLayout();quiet(peer,"leaving for Audi MMI must not request layout");
            AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),true);
            AltScreenCluster.flushLayout();quiet(peer,"main-screen return must settle");
            Thread.sleep(360);flush(peer,TOP);
            AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),true);
            AltScreenCluster.flushLayout();quiet(peer,"duplicate main-screen activation must not resend");
            AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),false);
            AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),true);
            AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),false);
            Thread.sleep(360);AltScreenCluster.flushLayout();quiet(peer,"leaving cancels pending return");

            // Later route, same video readiness; then a native reset hidden by debounce.
            route(peer, "route_state:n:0\n");
            route(peer, "route_state:n:3\n");
            AltScreenCluster.flushLayout(); quiet(peer, "loading is not a settled route");
            route(peer, "route_state:n:1\n"); flush(peer, TOP);
            route(peer, "route_generation:n:101\nroute_state:n:1\n"); flush(peer, TOP);
            route(peer, "route_generation:n:101\nroute_state:n:1\n");
            AltScreenCluster.flushLayout(); quiet(peer, "same route generation replay");
            route(peer, "route_generation:n:bad\n");
            AltScreenCluster.flushLayout(); quiet(peer, "invalid generation must not manufacture a route");
            route(peer, "route_state:n:5\n");
            AltScreenCluster.flushLayout(); quiet(peer, "reroute still calculating");
            route(peer, "route_state:n:1\n"); flush(peer, TOP);
            route(peer, "source_name:s:Apple Maps\n"); flush(peer, TOP);
            route(peer, "source_name:s:Apple Maps\n");
            AltScreenCluster.flushLayout(); quiet(peer, "duplicate source");
            route(peer, "route_state:n:0\n");
            route(peer, "route_state:n:6\n"); flush(peer, TOP);
            route(peer, "route_state:n:1\n");
            AltScreenCluster.flushLayout(); quiet(peer, "settled 6-to-1 is not another route");

            route(peer, "route_generation:n:102\n");
            route(peer, "route_state:n:0\n");
            AltScreenCluster.flushLayout(); quiet(peer, "route ended before worker ran");
            route(peer, "source_supports_rg:n:0\nroute_state:n:1\n");
            AltScreenCluster.flushLayout(); quiet(peer, "unsupported source");
            route(peer, "source_supports_rg:n:1\nroute_state:n:1\n"); flush(peer, TOP);
            route(peer, "route_generation:n:103\n");
            route(peer, "disconnect_reason:s:test\n");
            AltScreenCluster.flushLayout(); quiet(peer, "disconnect cancels pending route");

            AltScreenCluster.setVideoReady(false);
            AltScreenCluster.onPresentation(false);
            route(peer, "route_generation:n:104\nroute_state:n:1\n");
            AltScreenCluster.flushLayout(); quiet(peer, "route without video");
            AltScreenCluster.setVideoReady(true);
            flush(peer, TOP);
            AltScreenCluster.flushLayout(); quiet(peer, "route and video edge coalesce");
            route(peer, "route_generation:n:105\n");
            AltScreenCluster.setVideoReady(false);
            AltScreenCluster.flushLayout(); quiet(peer, "video disabled with pending route");
            AltScreenCluster.setVideoReady(true); flush(peer, TOP);

            // No preference reload until receiver reconnect, even if HMI modules restart.
            String right = BASE + "?maneuverLayout=rightaligned";
            write(preference, right);
            AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),true);
            Thread.sleep(360);flush(peer,TOP);
            route(peer, "route_generation:n:106\n"); flush(peer, TOP);
            AltScreenCluster.reapplyLayout(); expect(peer, TOP);
            AltScreenCluster.flushLayout(); quiet(peer, "manual action does not read saved layout");
            route(peer, "route_generation:n:107\n");
            AltScreenCluster.reapplyLayout(); expect(peer, TOP);
            AltScreenCluster.flushLayout(); quiet(peer, "manual and pending automatic request coalesce");
            CarplayBus.Observer retired = (CarplayBus.Observer)field(AltScreenCluster.class, "routeObserver").get(null);
            AltScreenCluster.stop();
            try { AltScreenCluster.reapplyLayout(); throw new AssertionError("manual action while disabled"); }
            catch (java.io.IOException expected) { }
            AltScreenCluster.start();
            try { AltScreenCluster.reapplyLayout(); throw new AssertionError("manual action without video"); }
            catch (java.io.IOException expected) { }
            AltScreenCluster.setVideoReady(true); flush(peer, TOP);
            byte[] stale = "route_state:n:1\n".getBytes("UTF-8");
            retired.onFrame(bus.connectionGeneration(), CarplayBus.EVT_RGD_UPDATE, 0, stale, stale.length);
            AltScreenCluster.flushLayout(); quiet(peer, "retired module callback");

            int oldConnection = bus.connectionGeneration();
            AltScreenCluster.MainScreenEvent oldMainScreen=AltScreenCluster.captureMainScreenEvent();
            AltScreenCluster.onPresentation(false);
            AltScreenCluster.onPresentation(true);
            Socket old = peer; peer = connect(); old.close();
            try { AltScreenCluster.reapplyLayout(); throw new AssertionError("manual action reused old receiver URL"); }
            catch (java.io.IOException expected) { }
            check(!bus.sendBinary(CarplayBus.CMD_ALT_UICTX, TOP.getBytes("UTF-8"), oldConnection),
                "stale async send accepted for replacement receiver");
            flush(peer, right);
            AltScreenCluster.onMainScreen(oldMainScreen,false);
            AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),true);
            Thread.sleep(360);AltScreenCluster.flushLayout();quiet(peer,"old main-screen event seeded new session");
            CarplayBus.Observer current = (CarplayBus.Observer)field(AltScreenCluster.class, "routeObserver").get(null);
            current.onFrame(oldConnection, CarplayBus.EVT_RGD_UPDATE, 0, stale, stale.length);
            AltScreenCluster.flushLayout();
            quiet(peer, "only one reconnect request without video edge");
            for (String selected : new String[]{BASE + "?showETA=no", BASE, TOP}) {
                write(preference, selected);
                old = peer; peer = connect(); old.close();
                flush(peer, selected);
                route(peer, "route_state:n:1\nroute_generation:n:1\n"); flush(peer, selected);
                route(peer, "route_generation:n:2\n"); flush(peer, selected);
                AltScreenCluster.onPresentation(false);AltScreenCluster.onPresentation(true);
                AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),false);
                AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),true);
                Thread.sleep(360);flush(peer,selected);
                AltScreenCluster.flushLayout();quiet(peer,"View and main-screen return must coalesce");
            }

            Constructor<Preferences> constructor = Preferences.class.getDeclaredConstructor(File.class, File.class);
            constructor.setAccessible(true);
            Preferences nativePrefs = constructor.newInstance(nativePreference.toFile(), preference.toFile());
            singleton.set(null, nativePrefs);
            nativePrefs.set(Setting.LAYOUT, 2);
            old = peer; peer = connect(); old.close();
            flush(peer, BASE + "?showETA=no");
            nativePrefs.set(Setting.LAYOUT, 3);
            route(peer, "route_state:n:1\n"); flush(peer, BASE + "?showETA=no");
            old = peer; peer = connect(); old.close();
            flush(peer, BASE);
            byte[] validPreferences = Files.readAllBytes(nativePreference);
            write(nativePreference, "format=broken\n"); nativePrefs.refresh();
            old = peer; peer = connect(); old.close();
            AltScreenCluster.flushLayout(); quiet(peer, "invalid native preferences must not fall back to top");
            Files.write(nativePreference, validPreferences); nativePrefs.refresh();
            route(peer, "route_state:n:1\n");
            AltScreenCluster.MainScreenEvent retiredMainScreen=AltScreenCluster.captureMainScreenEvent();
            AltScreenCluster.flushLayout(); quiet(peer, "repair still requires reconnect");
            nativePrefs.set(Setting.LAYOUT, 0);
            old = peer; peer = connect(); old.close(); flush(peer, TOP);

            // Block the preference lookup: teardown/route callbacks must not wait for it,
            // and that old lookup must not enqueue after the module has been replaced.
            field(AltScreenCluster.class, "urlConnection").setInt(null, -1);
            route(peer, "route_generation:n:3\nroute_state:n:1\n");
            final Throwable[] failure = {null};
            Thread lookup = new Thread(new Runnable() {
                public void run() {
                    try { AltScreenCluster.flushLayout(); } catch (Throwable t) { failure[0] = t; }
                }
            });
            synchronized (Preferences.class) {
                lookup.start();
                end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (lookup.getState() != Thread.State.BLOCKED) {
                    check(System.nanoTime() < end, "lookup must reach preference read"); Thread.sleep(1);
                }
                route(peer, "route_state:n:0\n");
                AltScreenCluster.stop();
                AltScreenCluster.start();
                AltScreenCluster.setVideoReady(true);
            }
            lookup.join(3000);
            check(!lookup.isAlive() && failure[0] == null, "lookup worker failed");
            quiet(peer, "stale file lookup leaked through stop/start");
            flush(peer, TOP);
            route(peer, "route_state:n:1\n");
            AltScreenCluster.onPresentation(false);
            AltScreenCluster.onPresentation(true);
            AltScreenCluster.stop();
            AltScreenCluster.flushLayout(); quiet(peer, "Master Off cancels pending layout");
            AltScreenCluster.start();AltScreenCluster.setVideoReady(true);flush(peer,TOP);
            AltScreenCluster.onMainScreen(retiredMainScreen,false);
            AltScreenCluster.onMainScreen(AltScreenCluster.captureMainScreenEvent(),true);
            Thread.sleep(360);AltScreenCluster.flushLayout();quiet(peer,"retired module main-screen event");
            check(primaryCalls >= 75, "layout observer stole route frames from primary listener");
        } finally {
            AltScreenCluster.stop(); bus.stop();
            if (peer != null) peer.close();
            singleton.set(null, previousPreferences);
            Files.deleteIfExists(nativePreference); Files.deleteIfExists(preference); Files.delete(root);
        }
        System.out.println("AltScreenLayoutLifecycleTest: repeated routes on live video, generations, reroutes, sources, "
            + "coalesced View edges, no polling spam, explicit presets, reconnect, cancellation and nonblocking callbacks PASS");
    }
}
