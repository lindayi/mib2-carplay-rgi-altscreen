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
    volatile boolean rejectRefresh, ignoreVideoWrites;
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
            int requested = ((Integer)args[0]).intValue();
            switches.add(Integer.valueOf(requested));
            if (requested == 82 && rejectRefresh) throw new IllegalStateException("refresh context rejected");
            if (requested != 81 || !ignoreVideoWrites) current = requested;
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

    void awaitBinding(int revision) throws Exception {
            long end=System.currentTimeMillis()+5000L;
            while(System.currentTimeMillis()<end){
                if(ScreenModule.isRendererBindingApplied(revision))return;
                Thread.sleep(20L);
            }
            throw new AssertionError("unconfirmed binding "+revision+" switches="+switches);
        }

        static Field field(Class type,String name)throws Exception {
            for(Class c=type;c!=null;c=c.getSuperclass())try{
                Field f=c.getDeclaredField(name);f.setAccessible(true);return f;
            }catch(NoSuchFieldException missing){}
            throw new NoSuchFieldException(name);
        }

        static sun.misc.Unsafe unsafe()throws Exception {
            Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
            return (sun.misc.Unsafe)f.get(null);
        }

        static void compositionContract(AltScreenContextTest capture)throws Exception {
            Class type=de.audi.tghu.fwhmi.DisplayManagerMIB2High.class;
            Object actual=unsafe().allocateInstance(type);
            field(type,"framework").set(actual,capture.proxy(de.audi.atip.base.IFrameworkAccess.class));
            Method define=type.getDeclaredMethod("defineContexts");define.setAccessible(true);define.invoke(actual);
            Object[] contexts=(Object[])field(type,"dc").get(actual);
            int[][] expected={{98,101,102,33},{98,101,102,3},{101,102,3}};
            for(int i=0;i<3;i++){
                int[] layers=null;
                for(Field f:contexts[80+i].getClass().getFields())
                    if(f.getType()==int[].class)layers=(int[])f.get(contexts[80+i]);
                check(Arrays.equals(layers,expected[i]),"actual context "+(80+i)+" composition changed");
            }
        }

        static final class Renderer extends com.luka.carplay.routeguidance.RendererServer {
            boolean framed;int peer=1;
            public boolean connect(){return true;}
            public boolean isReady(){return true;}
            public boolean isFrameReady(){return framed;}
            public int connectionGeneration(){return peer;}
            public boolean sendVisibleArea(int x,int y,int w,int h){return true;}
            public boolean sendDisplayOptions(){return true;}
            public boolean sendRouteLabels(String distance,String road){return true;}
            public boolean sendManeuver(int icon,int dir,int angle,int side,int[] roads,int level,int mode,int perspective){return true;}
        }

        void rendererBinding()throws Exception {
            com.luka.carplay.routeguidance.BAPBridge bridge=new com.luka.carplay.routeguidance.BAPBridge();
            Renderer renderer=new Renderer();
            field(bridge.getClass(),"initialized").setBoolean(bridge,true);
            field(bridge.getClass(),"bapSessionStarted").setBoolean(bridge,true);
            field(bridge.getClass(),"csRef").set(bridge,unsafe().allocateInstance(de.audi.tghu.navi.app.cluster.ClusterService.class));
            field(bridge.getClass(),"rendererClient").set(bridge,renderer);
            switches.clear();
            check(!bridge.preparePresentation(),"unframed renderer became ready");
            Thread.sleep(100L);check(switches.isEmpty(),"unframed renderer requested a context");
            renderer.framed=true;
            check(!bridge.preparePresentation(),"unconfirmed binding became ready");
            int ticket=field(bridge.getClass(),"rendererBinding").getInt(bridge);
            awaitBinding(ticket);
            check(bridge.preparePresentation(),"confirmed renderer not ready");
            check(switches.contains(82) && !switches.contains(72),"renderer rebind lost video: "+switches);
            int before=switches.size();
            for(int i=0;i<5;i++)check(bridge.preparePresentation(),"ready renderer regressed");
            check(switches.size()==before,"successful binding repeated");
            renderer.peer++;
            check(!bridge.preparePresentation(),"rapid peer replacement reused old binding");
            int replacement=field(bridge.getClass(),"rendererBinding").getInt(bridge);
            check(replacement!=ticket,"peer replacement lost revision");
            awaitBinding(replacement);check(bridge.preparePresentation(),"replacement binding not ready");
            bridge.onRouteEnd();
            ScreenModule.setNavActive(false);
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
        compositionContract(capture);
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
        capture.rendererBinding();

        capture.switches.clear();capture.rejectRefresh=true;
        int fallback=ScreenModule.requestRendererBinding();capture.awaitBinding(fallback);
        check(capture.switches.contains(72),"rejected refresh had no verified fallback");
        capture.rejectRefresh=false;capture.ignoreVideoWrites=true;capture.switches.clear();
        int unconfirmed=ScreenModule.requestRendererBinding();
        long rejectionDeadline=System.currentTimeMillis()+3000L;
        while(!capture.switches.contains(81) && System.currentTimeMillis()<rejectionDeadline)Thread.sleep(20L);
        check(capture.switches.contains(81),"fixture never attempted final context");
        Thread.sleep(400L);
        check(!ScreenModule.isRendererBindingApplied(unconfirmed),"unreadable final context was acknowledged");
        capture.ignoreVideoWrites=false;capture.awaitBinding(unconfirmed);

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
            check(!ScreenModule.isRendererBindingApplied(unconfirmed),"disconnect retained old renderer binding");
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
        System.out.println("AltScreenContextTest: real context composition, map-preserving renderer rebind, readback/fallback, peer replacement, video loss, drift, reconnect and disconnect PASS");
    }
}
