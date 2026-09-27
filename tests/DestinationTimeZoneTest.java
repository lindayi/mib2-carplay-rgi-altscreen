import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.CarPlayApp;
import com.luka.carplay.core.FrameworkRef;
import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.rgd.*;
import de.audi.app.terminalmode.IContext;
import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import de.audi.atip.metrics.DateMetric;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.TimeZone;

public final class DestinationTimeZoneTest {
    static final long ETA=1735688700L;
    static class Output implements InvocationHandler {
        String text;
        long arrival;
        public Object invoke(Object p,Method m,Object[] a) {
            if(m.getName().equals("updateCurrentPositionInfo"))text=(String)a[0];
            if(m.getName().equals("updateTimeToDestination")) {
                check((Integer)a[0]==1,"native ETA changed to duration");
                arrival=(Long)a[2];
            }
            return null;
        }
    }
    static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
    static Field field(Class<?> c,String key)throws Exception{Field f=c.getDeclaredField(key);f.setAccessible(true);return f;}
    static void parse(RouteGuidance rg,String data)throws Exception {
        byte[] bytes=data.getBytes("UTF-8");
        Method m=RouteGuidance.class.getDeclaredMethod("parse",CarplayBus.Data.class);m.setAccessible(true);
        m.invoke(rg,CarplayBus.parseText(bytes,bytes.length));
    }
    static RouteGuidance.State state(RouteGuidance rg)throws Exception{return (RouteGuidance.State)field(RouteGuidance.class,"state").get(rg);}
    static void render(BAPBridge bridge,Output out,RouteGuidance.State state,int offset,String clock)throws Exception {
        state.markAllDirtyForReplay();
        check(bridge.refreshInfoPresentation(state,1),"clock presentation rejected");
        check(out.arrival==ETA+offset*60L,"wrong clock epoch: "+out.arrival);
        check(out.text.startsWith("\u25cc "+clock),"wrong arrival text: "+out.text);
        check(out.text.endsWith("10 min"),"timezone changed UTC duration: "+out.text);
    }
    public static void main(String[] args)throws Exception {
        Field fw=field(CarPlayApp.class,"fwRef");Object prior=fw.get(null);
        TimeZone priorZone=TimeZone.getDefault();
        int priorFormat=DateMetric.timeFormat;
        IFrameworkAccess framework=(IFrameworkAccess)Proxy.newProxyInstance(DestinationTimeZoneTest.class.getClassLoader(),
            new Class[]{IFrameworkAccess.class},(p,m,a)-> {
                if(m.getName().equals("getUTCTime"))return (ETA-600)*1000L;
                if(m.getName().equals("convertUTCTimeToLocalTime"))return (Long)a[0]-480*60000L;
                if(m.getReturnType()==boolean.class)return false;
                if(m.getReturnType()==int.class)return 0;
                if(m.getReturnType()==long.class)return 0L;
                return null;
            });
        IContext context=(IContext)Proxy.newProxyInstance(DestinationTimeZoneTest.class.getClassLoader(),
            new Class[]{IContext.class},(p,m,a)->m.getName().equals("getFramework")?framework:null);
        fw.set(null,new FrameworkRef(context));
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+09:00"));
        DateMetric.timeFormat=0;
        BAPBridge bridge=new BAPBridge();Output out=new Output();
        field(BAPBridge.class,"initialized").setBoolean(bridge,true);
        field(BAPBridge.class,"bapSessionStarted").setBoolean(bridge,true);
        field(BAPBridge.class,"appConnectorNavi").set(bridge,Proxy.newProxyInstance(
            DestinationTimeZoneTest.class.getClassLoader(),new Class[]{CombiBAPServiceNavi.class},out));
        field(ScreenModule.class,"smallScreenViewArea").setBoolean(null,false);
        try {
            RouteGuidance rg=new RouteGuidance();
            parse(rg,"route_generation:n:100\nroute_state:n:1\neta_seconds:n:"+ETA+"\ndestination:s:First\n");
            RouteGuidance.State s=state(rg);
            render(bridge,out,s,-480,"15:45");
            check(!out.text.contains("dest"),"fallback falsely marked destination-local");
            int[] offsets={330,345,-210,0,765,-480};
            String[] clocks={"05:15","05:30","20:15","23:45","12:30","15:45"};
            for(int i=0;i<offsets.length;i++) {
                parse(rg,"destination_timezone_minutes:n:"+offsets[i]+"\n");
                render(bridge,out,s,offsets[i],clocks[i]);
                check(out.text.contains(" dest")== (offsets[i]!=-480),"cross-zone label mismatch");
            }
            parse(rg,"destination_timezone_minutes:n:330\n");
            DateMetric.timeFormat=11;
            render(bridge,out,s,330,"5:15 AM");DateMetric.timeFormat=0;
            s.clearDirty();parse(rg,"destination_timezone_minutes:n:345\n");
            check(s.dirtyMask==RouteGuidance.State.DIRTY_DEST_TIMEZONE,"zone-only update not dirty");
            bridge.update(s);
            check(out.arrival==ETA+345*60L && out.text.startsWith("\u25cc 05:30"),
                "zone-only delta did not refresh existing clock surfaces");
            render(bridge,out,s,345,"05:30");
            parse(rg,"destination:s:Second\n");
            check(s.destinationTimeZoneMinutes==RouteGuidance.State.UNKNOWN_TIMEZONE,"changed destination retained zone");
            render(bridge,out,s,-480,"15:45");
            parse(rg,"destination:s:Third\ndestination_timezone_minutes:n:330\n");
            render(bridge,out,s,330,"05:15");
            parse(rg,"route_generation:n:101\nroute_state:n:1\neta_seconds:n:"+ETA+"\n");
            check(s.destinationTimeZoneMinutes==RouteGuidance.State.UNKNOWN_TIMEZONE,"new route retained zone");
            render(bridge,out,s,-480,"15:45");
            parse(rg,"destination_timezone_minutes:n:9999\n");render(bridge,out,s,-480,"15:45");
            parse(rg,"destination_timezone_minutes:n:-1\n");
            check(s.destinationTimeZoneMinutes==-1,"negative minute confused with unknown");
            parse(rg,"source_supports_rg:n:0\ndestination_timezone_minutes:n:330\n");
            check(s.destinationTimeZoneMinutes==RouteGuidance.State.UNKNOWN_TIMEZONE,"hard clear retained zone");
            s.reset();check(s.destinationTimeZoneMinutes==RouteGuidance.State.UNKNOWN_TIMEZONE,"session reset retained zone");
            if(args.length>0) {
                RouteGuidance nativeRg=new RouteGuidance();
                String[] names={"plus330","retained","minus210","zero","invalid","destination-change","hard-clear","new-generation"};
                int[] expected={330,330,-210,0,-480,-480,-480,-480};
                String[] expectedClocks={"05:15","05:15","20:15","23:45","15:45","15:45","15:45","15:45"};
                for(int i=0;i<names.length;i++) {
                    parse(nativeRg,new String(Files.readAllBytes(Paths.get(args[0],"clock-"+names[i]+".txt")),"UTF-8"));
                    render(bridge,out,state(nativeRg),expected[i],expectedClocks[i]);
                }
            }
        } finally {fw.set(null,prior);TimeZone.setDefault(priorZone);DateMetric.timeFormat=priorFormat;}
        System.out.println("DestinationTimeZoneTest: signed/fractional offsets, rollover, 12/24h, HU fallback, UTC duration, dirty flags and lifecycle resets PASS");
    }
}
