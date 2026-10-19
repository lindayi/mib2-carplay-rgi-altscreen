import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.routeguidance.RouteGuidance;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import java.lang.reflect.*;

/** Real RGI initialization can acquire callbacks before engageTakeover succeeds. */
public final class RouteGuidancePartialLifecycleTest {
    static Object field(Class<?> owner, Object object, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    public static void main(String[] args) throws Exception {
        CombiBAPServiceNavi service = (CombiBAPServiceNavi)Proxy.newProxyInstance(
            CombiBAPServiceNavi.class.getClassLoader(), new Class[] { CombiBAPServiceNavi.class },
            (p,m,a) -> {
                if (m.getReturnType() == boolean.class) return false;
                if (m.getReturnType() == int.class) return 0;
                return null;
            });
        RouteGuidance rg = new RouteGuidance(true);
        if (!rg.init(service) || rg.isRunning()) throw new AssertionError("partial initialization setup");
        Object view = field(RouteGuidance.class, rg, "viewAreaModeListener");
        if (field(ScreenModule.class, null, "viewAreaModeListener") != view)
            throw new AssertionError("initialization did not acquire view listener");
        rg.stop();
        if (field(ScreenModule.class, null, "viewAreaModeListener") == view
                || field(ScreenModule.class, null, "infoModeListener")
                    == field(RouteGuidance.class, rg, "infoModeListener"))
            throw new AssertionError("partial initialization left stale callbacks");
        rg.stop();
        System.out.println("RouteGuidancePartialLifecycleTest: PASS");
    }
}
