import com.luka.carplay.rgd.*;
import de.audi.atip.metrics.Distance;
import java.lang.reflect.Method;

public final class RouteLabelsBridgeTest {
    static void check(boolean ok,String why) { if(!ok)throw new AssertionError(why); }
    static byte[] latest(ManeuverChainAudit a) throws Exception {
        Object[] q=(Object[])a.get(a.renderer,"writeQueue");
        int head=((Integer)a.get(a.renderer,"writeHead")).intValue();
        int count=((Integer)a.get(a.renderer,"writeCount")).intValue();
        return (byte[])a.get(q[(head+count-1)%q.length],"packet");
    }
    static String text(byte[] p,int offset,int len) throws Exception {return new String(p,offset,len,"UTF-8");}
    public static void main(String[] args) throws Exception {
        ManeuverChainAudit a=new ManeuverChainAudit();
        Distance.setSystemUnit(Distance.KM);
        RouteGuidance.State s=a.state(2,90,0,0,0,null);
        s.routeState=1;s.routeGeneration=42;s.distManeuverM=300;s.mAfterRoad[0]="Main Street";
        Method labels=a.bridge.getClass().getDeclaredMethod("updateRendererLabels",RouteGuidance.State.class);
        labels.setAccessible(true);
        check((Boolean)a.sendRenderer.invoke(a.bridge,s,0),"maneuver accepted");
        labels.invoke(a.bridge,s);
        byte[] p=latest(a);
        check(p[0]==15 && text(p,4,p[2]).equals("300 m") && text(p,16,p[3]).equals("Main Street"),"matching labels");
        Distance.setSystemUnit(Distance.MILES);
        labels.invoke(a.bridge,s);p=latest(a);
        String imperial=text(p,4,p[2]);
        check(imperial.endsWith(" ft") || imperial.endsWith(" mi") || imperial.endsWith(" yd"),"MMI imperial units");
        Distance.setSystemUnit(Distance.KM);
        s.distManeuverM=150;s.mExitInfo[0]="Exit 12";
        labels.invoke(a.bridge,s);p=latest(a);
        check(text(p,4,p[2]).equals("150 m") && text(p,16,p[3]).equals("Exit 12"),"live distance and exit");
        a.set(a.bridge,"rendererManeuverPending",Boolean.TRUE);
        labels.invoke(a.bridge,s);p=latest(a);
        check(p[2]==0 && p[3]==0,"pending maneuver withdraws old labels");
        a.set(a.bridge,"rendererManeuverPending",Boolean.FALSE);
        s.distManeuverM=-1;s.mAfterRoad[0]=s.mExitInfo[0]=s.mName[0]="";
        s.currentRoad="Wrong Road";
        labels.invoke(a.bridge,s);p=latest(a);
        check(p[2]==0 && p[3]==0,"missing values clear independently of current road");
        s.distManeuverM=200;s.mAfterRoad[0]="Second Street";s.mVer[0]++;
        a.set(a.bridge,"rendererManeuverPending",Boolean.TRUE);
        int before=((Integer)a.get(a.renderer,"writeCount")).intValue();
        labels.invoke(a.bridge,s);
        check(((Integer)a.get(a.renderer,"writeCount")).intValue()==before,"no labels on unaccepted maneuver");
        check((Boolean)a.sendRenderer.invoke(a.bridge,s,0),"next maneuver accepted");
        labels.invoke(a.bridge,s);p=latest(a);
        check(text(p,16,p[3]).equals("Second Street"),"labels follow next maneuver");
        Method stop=a.bridge.getClass().getDeclaredMethod("stopCustomRenderer",Boolean.TYPE);
        stop.setAccessible(true);stop.invoke(a.bridge,true);p=latest(a);
        check(p[0]==15 && p[2]==0 && p[3]==0,"route end clears labels even while preserving surface");
        System.out.println("RouteLabelsBridgeTest: real bridge ordering, distance updates, missing data, route-end clear PASS");
    }
}
