import com.luka.carplay.cluster.ClusterLayerController;
import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.framework.Log;
import de.audi.tghu.fwhmi.IDisplayManagerKombiControl;
import java.lang.reflect.*;
import java.util.*;

/** Exercise real layer writes/context decisions with the VC's recovered event sequence. */
public final class ClusterKdkSyncTest implements InvocationHandler {
    final Map opacity = Collections.synchronizedMap(new HashMap());
    volatile boolean intermediate;
    boolean failBacking;
    int cropWidth;
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static void field(String name, Object value) throws Exception {
        Class type = ScreenModule.class;
        if (name.equals("connected") || name.equals("navActive")) {
            type = com.luka.carplay.cluster.ClusterStateController.class;
            name = name.equals("connected") ? "carPlaySessionActive" : "rgiPresentationActive";
        }
        Field f=type.getDeclaredField(name); f.setAccessible(true);f.set(null,value);
    }
    public Object invoke(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("setOpacity")) {
            if(failBacking && ((Integer)args[0]).intValue()!=98) {
                failBacking=false;
                throw new IllegalStateException("injected backing-write failure");
            }
            opacity.put(args[0],args[2]);
            if(((Integer)args[0]).intValue()==98 && ((Integer)args[2]).intValue()>0
                    && ((Integer)args[2]).intValue()<100) intermediate=true;
        }
        if (method.getName().equals("setCropping")) cropWidth=((Integer)args[4]).intValue();
        Class type=method.getReturnType();
        if(type==Boolean.TYPE) return Boolean.FALSE;
        if(type==Integer.TYPE) return Integer.valueOf(0);
        return null;
    }
    int opacity(int id) { Integer v=(Integer)opacity.get(Integer.valueOf(id)); return v==null?-1:v.intValue(); }
    void visible(int maneuver,int sport,int popup) throws Exception {
        long deadline=System.currentTimeMillis()+1500;
        while((opacity(98)!=maneuver || opacity(101)!=sport || opacity(102)!=popup)
                && System.currentTimeMillis()<deadline)Thread.sleep(5);
        check(opacity(98)==maneuver && opacity(101)==sport && opacity(102)==popup,
            "unexpected opacity: "+opacity);
    }
    static Field layerField(String name) throws Exception {
        Field f=ClusterLayerController.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    public static void main(String[] args) throws Exception {
        Log.setLevel(-1);
        ClusterKdkSyncTest capture=new ClusterKdkSyncTest();
        IDisplayManagerKombiControl dm=(IDisplayManagerKombiControl)Proxy.newProxyInstance(
            ClusterKdkSyncTest.class.getClassLoader(),new Class[]{IDisplayManagerKombiControl.class},capture);
        field("platformSupported",Boolean.TRUE);field("connected",Boolean.TRUE);
        field("navActive",Boolean.TRUE);
        ClusterLayerController.bind(dm,1);
        ClusterLayerController.onVcPresentation(true);
        ClusterLayerController.onVcVisibility(true);capture.visible(100,0,100);
        check(capture.intermediate,"upstream fade must render intermediate opacity, not jump");
        ClusterLayerController.onVcVisibility(false);capture.visible(0,0,0);
        synchronized(layerField("APPLY_LOCK").get(null)) {
            layerField("fading").setBoolean(null,true);
            layerField("fadeDm").set(null,dm);
            layerField("fadeTerminal").setInt(null,1);
            layerField("fadeBacking").setInt(null,102);
            layerField("fadeFrom").setInt(null,0);
            layerField("fadeTo").setInt(null,100);
            layerField("fadeStart").setLong(null,1000L);
            layerField("fadeDuration").setInt(null,200);
            Method step=ClusterLayerController.class.getDeclaredMethod("stepFade",Long.TYPE);
            step.setAccessible(true);
            capture.failBacking=true;
            step.invoke(null,Long.valueOf(1100L));
            check(capture.opacity(98)==50,"fixture must fail after the actual maneuver write");
            ClusterLayerController.onVcVisibility(false);
            check(capture.opacity(98)==0,"partial backing failure must not skip a later hide");
        }
        ClusterLayerController.onVcVisibility(true);capture.visible(100,0,100);
        // View request is not the stage event; it must not expose the destination rectangle.
        field("smallScreenViewArea",Boolean.TRUE); // target: boolean view-area flag
        ClusterLayerController.reapply();capture.visible(100,0,100);
        ClusterLayerController.onVcVisibility(false);capture.visible(0,0,0);
        check(ScreenModule.isNavActive(),"View fade must preserve active-route context");
        ClusterLayerController.onVcPresentation(false);capture.visible(0,0,0);
        check(capture.cropWidth==328,"VC midpoint moves hidden inTube crop");
        ClusterLayerController.onVcVisibility(true);capture.visible(100,100,0);
        // Duplicate callbacks and replays must not reveal the other backing.
        ClusterLayerController.onVcPresentation(false);ClusterLayerController.reapply();capture.visible(100,100,0);
        ClusterLayerController.onVcVisibility(false);
        ClusterLayerController.onVcPresentation(true);capture.visible(0,0,0);
        ClusterLayerController.onVcVisibility(true);capture.visible(100,0,100);
        ScreenModule.setNavActive(false);
        check(ScreenModule.isNavActive(),"route end retains context until VC hide");
        ClusterLayerController.reapply();capture.visible(100,0,100);
        // A restarted route cancels pending release; later View hide cannot stop it.
        ScreenModule.setNavActive(true);
        ClusterLayerController.onVcVisibility(false);
        check(ScreenModule.isNavActive(),"restart cancels old release");
        ClusterLayerController.onVcVisibility(true);
        ScreenModule.setNavActive(false);
        ClusterLayerController.onVcVisibility(false);capture.visible(0,0,0);
        check(!ScreenModule.isNavActive(),"VC hide releases ended route");
        ClusterLayerController.onVcVisibility(true);capture.visible(0,0,0);
        check(!ScreenModule.isNavActive(),"late visibility cannot resurrect ended route");
        // Release restores the stock hint-selected backing, not the CarPlay destination.
        field("connected",Boolean.FALSE); // target: no altscreen videoAvailable
        ClusterLayerController.apply(dm,1,null,true,100,true);capture.visible(0,100,0);
        System.out.println("ClusterKdkSyncTest: VC hide/midpoint/show, route end/restart, stale show, stock restore PASS");
    }
}
