package com.luka.carplay.routeguidance;

import com.luka.carplay.framework.Log;
import de.audi.atip.hmi.modelaccess.LabelModelApp;
import de.audi.atip.hmi.modelaccess.MetricsModelApp;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import de.audi.atip.metrics.DateMetric;
import de.audi.atip.metrics.Distance;
import de.audi.tghu.navi.app.Navigation;
import de.audi.tghu.navi.app.NavigationEnv;
import de.audi.tghu.navi.app.cluster.BAPDistanceFormatter;
import de.audi.tghu.navi.app.cluster.ClusterService;
import de.audi.tghu.navi.app.cluster.CombiBAPListener;
import de.audi.tghu.navi.app.cluster.KOMOService;
import de.esolutions.fw.util.commons.job.DispatcherBase;
import de.esolutions.fw.util.commons.job.Job;
import java.lang.reflect.*;
import java.util.*;
import org.dsi.ifc.komoview.RouteInfoElement;
import sun.misc.Unsafe;

/** Real ClusterService setters and stock listener replay; framework/DSI endpoints only are fake. */
public final class LowerBarKomoTest {
    static final Unsafe U;
    static {
        try { Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);U=(Unsafe)f.get(null); }
        catch(Exception e){throw new AssertionError(e);}
    }
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    static void set(Object o,String name,Object v)throws Exception{RgiDeliveryRecoveryTest.set(o,name,v);}
    static Object get(Object o,String name)throws Exception{return RgiDeliveryRecoveryTest.get(o,name);}
    static void invoke(Object o,String name,Class[] types,Object... values)throws Exception {
        Method m=ClusterService.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(o,values);
    }
    static final class Queue extends DispatcherBase {
        List<Runnable> tasks;boolean dispatching,reject;
        Queue(){super(null,null,null,null);}
        public Job execute(Object task){
            if(reject)throw new IllegalStateException("queue fault");
            tasks.add((Runnable)task);return null;
        }
        void drain(){
            dispatching=true;
            try{while(!tasks.isEmpty())tasks.remove(0).run();}
            finally{dispatching=false;}
        }
    }
    static final class Navi extends Navigation {
        Navi(){super(null);}
        void install(Queue q,ClusterService cs){instance=this;dispatcher=q;clusterService=cs;}
    }
    static final class Env extends NavigationEnv {
        LabelModelApp label;MetricsModelApp metric;
        Env(){super(null,null,null);}
        public LabelModelApp getLabelModel(int id){return label;}
        public MetricsModelApp getMetricsModel(int id){return metric;}
    }
    static final class Komo extends KOMOService {
        Queue queue;String road;int writes,failRoad;long distance;boolean etaValid;short hour,minute;
        java.util.concurrent.CountDownLatch entered, release;
        Komo(){super(null,null,null);}
        void write(){check(queue.dispatching,"model/KOMO write outside NavigationJobs");writes++;}
        public void setCurrentStreet(String s){
            if(entered!=null){
                entered.countDown();
                try{if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("blocked fixture timed out");}
                catch(InterruptedException e){throw new AssertionError(e);}
            }
            write();if(failRoad-->0)throw new IllegalStateException("road fault");road=s;
        }
        public void setETA(int format,short day,short hour,short minute,boolean valid,boolean offset){
            write();this.hour=hour;this.minute=minute;etaValid=valid;
        }
        public void setRTT(short hour,short minute,boolean valid){write();}
        public void setDistanceToDestination(long value,int unit,boolean valid){write();distance=value;}
    }
    static final class Service extends ClusterService {
        int failFlush,flushes;
        Service(){super(null,null,null,null,null,null);}
        public void updateKOMOFollowInfo(){
            flushes++;if(failFlush-->0)throw new IllegalStateException("flush fault");
        }
    }
    static final class Fixture {
        final Queue q=(Queue)U.allocateInstance(Queue.class);
        final Navi n=(Navi)U.allocateInstance(Navi.class);
        final Service cs=(Service)U.allocateInstance(Service.class);
        final Komo komo=(Komo)U.allocateInstance(Komo.class);
        final CombiBAPListener listener=(CombiBAPListener)U.allocateInstance(CombiBAPListener.class);
        final Map<String,Object[]> bap=new HashMap<>();
        final GatedCombiService gate;
        String label;
        Fixture()throws Exception {
            q.tasks=new ArrayList<>();komo.queue=q;n.install(q,cs);
            Constructor<?> c=Class.forName("com.luka.carplay.routeguidance.BAPBridge$SilentLogChannel").getDeclaredConstructor();
            c.setAccessible(true);de.audi.atip.log.LogChannel log=(de.audi.atip.log.LogChannel)c.newInstance();
            Env env=(Env)U.allocateInstance(Env.class);
            InvocationHandler model=(p,m,a)->{
                check(q.dispatching,"model write outside NavigationJobs");
                if(m.getName().equals("setText"))label=(String)a[0];
                if(m.getReturnType()==boolean.class)return false;
                if(m.getReturnType()==int.class)return 0;
                return null;
            };
            env.label=(LabelModelApp)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{LabelModelApp.class},model);
            env.metric=(MetricsModelApp)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{MetricsModelApp.class},model);
            CombiBAPServiceNavi sink=(CombiBAPServiceNavi)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{CombiBAPServiceNavi.class},(p,m,a)->{bap.put(m.getName(),a);return null;});
            gate=new GatedCombiService(sink);gate.setCurrentPositionInfoBlocked(true);
            set(cs,"env",env);set(cs,"logChannel",log);set(cs,"komoService",komo);set(cs,"combiBAPListener",listener);
            set(cs,"etaDateMetric",new DateMetric(new Date(),1));set(cs,"rttDateMetric",new DateMetric(new Date(),3));
            set(cs,"distanceToDestination",new Distance(0.0F,1));
            set(cs,"bapDistanceFormatter",new BAPDistanceFormatter(log));
            set(cs,"followInfoRIE",U.allocateInstance(RouteInfoElement.class));
            set(listener,"env",env);set(listener,"logChannel",log);set(listener,"combiservice",gate);
            set(listener,"bapDistanceFormatter",new BAPDistanceFormatter(log));
        }
        void nativeRoad(String value){q.dispatching=true;try{cs.updateCurrentStreet(value);}finally{q.dispatching=false;}}
        void nativeDistance(int value)throws Exception {
            q.dispatching=true;
            try{invoke(cs,"updateDistanceToDestination",new Class[]{int.class,boolean.class},value,false);}
            finally{q.dispatching=false;}
        }
        void nativeArrival(long value)throws Exception {
            q.dispatching=true;
            try{invoke(cs,"updateArrivalTime",new Class[]{boolean.class,long.class,boolean.class},true,value,false);}
            finally{q.dispatching=false;}
        }
        void stockReplay()throws Exception {
            q.dispatching=true;
            try{
                for(String name:new String[]{"updateCurrentPositionInfo","updateDistanceToDestination","updateTimeToDestination"}){
                    Method m=CombiBAPListener.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(listener);
                }
            }finally{q.dispatching=false;}
        }
    }
    static void retry(LowerBarKomo owner)throws Exception{set(owner,"lastAttempt",0L);LowerBarKomo.retryPending();}
    public static void main(String[] args)throws Exception {
        Log.setLevel(-1);
        Fixture f=new Fixture();
        f.nativeRoad("Audi original");f.nativeDistance(1500);f.nativeArrival(1710000000000L);
        Object stockDistance=get(f.listener,"destinationDistance");
        Object stockTime=get(f.listener,"destinationTime");
        LowerBarKomo a=new LowerBarKomo(f.cs);
        int all=RouteGuidance.State.DIRTY_CURRENT_ROAD|RouteGuidance.State.DIRTY_DIST_DEST|RouteGuidance.State.DIRTY_ETA;
        a.update(all,"Phone",900,1710003600000L,true);
        check(f.komo.road.equals("Audi original") && f.q.tasks.size()==1,"worker mutated models");
        a.update(RouteGuidance.State.DIRTY_CURRENT_ROAD,"Phone latest",900,1710003600000L,true);
        check(f.q.tasks.size()==1,"updates not coalesced");f.q.drain();
        check(f.label.equals("Phone latest") && f.komo.road.equals("Phone latest"),"missing phone model/KOMO road");
        check(get(f.listener,"destinationDistance").equals(stockDistance)
            && get(f.listener,"destinationTime").equals(stockTime),"project changed stock listener cache");
        check(get(f.listener,"currentPositionInfo").equals("Audi original"),"project polluted native road");
        f.stockReplay();
        f.nativeRoad("Audi newer");f.nativeDistance(1700);f.nativeArrival(1710007200000L);
        check(f.label.equals("Phone latest"),"native model callback bypassed project ownership");
        f.stockReplay();f.gate.setCurrentPositionInfoBlocked(false);
        check(f.bap.get("updateCurrentPositionInfo")[0].equals("Audi newer"),"updateAll replayed phone road as native");
        check(f.bap.get("updateTimeToDestination")[2].equals(get(f.listener,"destinationTime")),"native time replay lost");
        a.clear();f.q.drain();
        check(f.komo.road.equals("Audi newer") && f.label.equals("Audi newer"),"latest native road not restored");
        check(get(f.cs,"carplayDistanceOwner")==null && get(f.cs,"carplayArrivalOwner")==null,"travel ownership leaked");

        LowerBarKomo old=new LowerBarKomo(f.cs);
        f.komo.failRoad=1;old.update(all,"partial",500,1710010800000L,false);f.q.drain();
        check(get(f.cs,"carplayRoadOwner")==old,"partial setter failure lost cleanup responsibility");
        old.clear();retry(old);f.komo.failRoad=1;f.q.drain();
        LowerBarKomo next=new LowerBarKomo(f.cs);
        next.update(all,"replacement",600,1710014400000L,false);f.q.drain();
        retry(old);f.q.drain();
        check(f.komo.road.equals("replacement"),"old failed cleanup erased new owner");
        f.cs.failFlush=1;next.clear();f.q.drain();
        check(get(f.cs,"carplayDistanceOwner")==next,"failed flush released ownership");
        retry(next);f.q.drain();
        check(get(f.cs,"carplayDistanceOwner")==null,"failed clear did not retry");

        LowerBarKomo stale=new LowerBarKomo(f.cs);
        stale.update(all,"stale",500,1710010800000L,false);
        Service replacement=(Service)U.allocateInstance(Service.class);f.n.install(f.q,replacement);
        int writes=f.komo.writes;f.q.drain();
        check(f.komo.writes==writes,"old queued work crossed service generation");
        f.n.install(f.q,f.cs);
        LowerBarKomo rejected=new LowerBarKomo(f.cs);f.q.reject=true;
        rejected.update(all,"retry",600,1710014400000L,false);f.q.reject=false;retry(rejected);f.q.drain();
        check(f.komo.road.equals("retry"),"queue failure lost latest work");
        rejected.clear();f.q.drain();

        LowerBarKomo blocked=new LowerBarKomo(f.cs);
        f.komo.entered=new java.util.concurrent.CountDownLatch(1);f.komo.release=new java.util.concurrent.CountDownLatch(1);
        blocked.update(all,"blocked",600,1710014400000L,false);
        java.util.concurrent.atomic.AtomicReference<Throwable> workerError=new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker=new Thread(()->f.q.drain());worker.setUncaughtExceptionHandler((t,e)->workerError.set(e));worker.start();
        check(f.komo.entered.await(1,java.util.concurrent.TimeUnit.SECONDS),"worker did not enter setter");
        Thread close=new Thread(()->blocked.clear());close.start();close.join(500);
        boolean nonblocking=!close.isAlive();f.komo.release.countDown();close.join();worker.join();
        check(workerError.get()==null,"worker failed: "+workerError.get());
        check(nonblocking,"route callback waited for model worker");
        f.komo.entered=null;
        check(f.komo.road.equals("Audi newer"),"in-flight write lost queued restore");

        RgiDeliveryRecoveryTest.Fixture route=new RgiDeliveryRecoveryTest.Fixture();
        set(route.bridge,"csRef",f.cs);
        route.feed("current_road:s:RGI road\ndist_dest_m:n:1200\neta_seconds:n:1710014400\n");
        f.q.drain();check(f.komo.road.equals("RGI road"),"real RGI update did not reach KOMO models");
        route.bridge.onShutdown();f.q.drain();
        check(f.komo.road.equals("Audi newer"),"bridge shutdown did not restore native models");
        System.out.println("LowerBarKomoTest: model/BAP separation, real stock replay, coalescing, latest-native restore, partial failures, old-owner fencing and service replacement PASS");
    }
}
