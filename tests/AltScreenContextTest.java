import com.luka.carplay.cluster.AltScreenVideo;
import com.luka.carplay.core.FrameworkRef;
import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.framework.Log;
import de.audi.app.terminalmode.IContext;
import de.audi.tghu.fwhmi.IDisplayManagerKombiControl;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real ScreenModule worker against a fake DisplayManager: AltScreen video markers select ctx 81. */
public final class AltScreenContextTest implements InvocationHandler {
    final List switches = Collections.synchronizedList(new ArrayList());
    volatile int current = 74;
    volatile boolean pauseReconcile;
    final CountDownLatch paused = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
    Object dm;

    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }

    Object proxy(Class type) {
        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{type}, this);
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
        if (method.getName().equals("getDisplayManager")) return dm;
        if (method.getName().equals("switchContext") && ((Integer)args[1]).intValue() == 1) {
            current = ((Integer)args[0]).intValue();
            switches.add(Integer.valueOf(current));
        }
        if (method.getName().equals("getCurrentContextID")) {
            if (pauseReconcile && ScreenModule.isClusterContextWriterThread()) {
                paused.countDown();
                check(resume.await(5, TimeUnit.SECONDS), "resume context worker");
                pauseReconcile = false;
            }
            return Integer.valueOf(current);
        }
        Class type = method.getReturnType();
        if (type == Boolean.TYPE) return Boolean.FALSE;
        if (type == Integer.TYPE) return Integer.valueOf(0);
        if (type == Long.TYPE) return Long.valueOf(0);
        if (type.isInterface()) return proxy(type);
        return null;
    }

    static void set(Class c, String name, Object value) throws Exception {
        Field f = c.getDeclaredField(name); f.setAccessible(true); f.set(null, value);
    }
    static int contextFor(boolean connected, boolean video, boolean nav) throws Exception {
        Method m = ScreenModule.class.getDeclaredMethod("contextFor",
            new Class[]{Boolean.TYPE, Boolean.TYPE, Boolean.TYPE});
        m.setAccessible(true);
        return ((Integer)m.invoke(null, new Object[]{Boolean.valueOf(connected),
            Boolean.valueOf(video), Boolean.valueOf(nav)})).intValue();
    }

    void await(int ctx, String message) throws Exception {
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            if (current == ctx) return;
            Thread.sleep(20);
        }
        throw new AssertionError(message + " (actual ctx " + current + ", switches " + switches + ")");
    }

    public static void main(String[] args) throws Exception {
        Log.setLevel(-1);
        check(contextFor(false, true, true) == 74, "disconnected is always stock");
        check(contextFor(true, false, false) == 74, "idle session stays stock");
        check(contextFor(true, false, true) == 80, "nav without video uses the stock map");
        check(contextFor(true, true, false) == 81, "video without nav uses ctx 81");
        check(contextFor(true, true, true) == 81, "video wins over the stock map during nav");

        File dir = new File(System.getProperty("java.io.tmpdir"), "altscreen-ctx-" + System.nanoTime());
        check(dir.mkdirs(), "scratch dir");
        File active = new File(dir, "active"), ready = new File(dir, "ready");
        set(AltScreenVideo.class, "activePath", active.getPath());
        set(AltScreenVideo.class, "readyPath", ready.getPath());

        AltScreenContextTest capture = new AltScreenContextTest();
        capture.dm = capture.proxy(IDisplayManagerKombiControl.class);
        FrameworkRef fw = new FrameworkRef((IContext)capture.proxy(IContext.class));
        final ScreenModule module = new ScreenModule();
        check(module.start(fw), "start actual screen module");
        capture.await(74, "session starts on stock");

        // Demand alone is not enough: the sidecar has not presented a frame yet.
        check(active.createNewFile(), "active marker");
        Thread.sleep(600);
        check(capture.current == 74 && !ScreenModule.isAltScreenVideo(), "demand without ready stays stock");
        check(ready.createNewFile(), "ready marker");
        capture.await(81, "ready video enters ctx 81");
        check(ScreenModule.isAltScreenVideo(), "video flag published");
        int i81 = capture.switches.lastIndexOf(Integer.valueOf(81));
        check(i81 > 0 && ((Integer)capture.switches.get(i81 - 1)).intValue() == 72,
            "ctx 81 is entered through the 72 bounce: " + capture.switches);

        // Route guidance during video keeps the video context; video loss falls back to the map.
        ScreenModule.setNavActive(true);
        Thread.sleep(400);
        check(capture.current == 81, "nav does not leave the video context");
        check(ready.delete(), "drop ready");
        capture.await(80, "video loss during nav falls back to stock map ctx 80");
        ScreenModule.setNavActive(false);
        capture.await(74, "route end without KDK visibility returns to stock");

        // Stock drift while owning ctx 81 is reconciled back.
        check(ready.createNewFile(), "ready again");
        capture.await(81, "video returns");
        capture.current = 74;
        capture.await(81, "drift is reconciled to ctx 81");

        // The worker must not need to observe the brief disconnected state.
        capture.pauseReconcile = true;
        check(capture.paused.await(5, TimeUnit.SECONDS), "pause worker before reconnect");
        try {
            module.stop();
            check(module.start(fw), "restart actual screen module with unchanged markers");
        } finally {
            capture.resume.countDown();
        }
        Thread.sleep(1000);
        capture.await(81, "rapid reconnect restores video without a marker edge");
        check(ScreenModule.isConnected() && ScreenModule.isAltScreenVideo(), "reconnected video state");

        // Disconnect releases the cluster even with stale markers on disk.
        module.stop();
        capture.await(74, "disconnect restores stock");
        Thread.sleep(400);
        check(capture.current == 74 && !ScreenModule.isAltScreenVideo(), "stale markers ignored while disconnected");

        active.delete(); ready.delete(); dir.delete();
        System.out.println("AltScreenContextTest: marker gating, 72 bounce, nav/video priority, video loss, drift, rapid reconnect, disconnect PASS");
    }
}
