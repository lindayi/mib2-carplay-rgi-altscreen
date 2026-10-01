package com.luka.carplay.core;

import com.luka.carplay.framework.Log;
import com.luka.carplay.settings.SettingsRuntime;
import de.audi.app.terminalmode.IContext;
import de.audi.app.terminalmode.osgi.IServiceManager;
import de.audi.atip.base.IFrameworkAccess;
import java.lang.reflect.*;
import java.util.*;
import org.dsi.ifc.base.*;
import org.dsi.ifc.carvehiclestates.*;
import org.dsi.ifc.cardriverassistance.*;
import org.dsi.ifc.global.CarBCSpeed;
import org.dsi.ifc.trafficregulation.*;
import org.osgi.framework.*;

public final class SpeedSourceDiagnosticsTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void set(Class<?> c,String name,Object value) throws Exception {
        Field f=c.getDeclaredField(name);f.setAccessible(true);f.set(null,value);
    }
    static Object proxy(Class<?> type,InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    static Object identity(Object p,Method m,Object[] a) {
        if(m.getName().equals("equals"))return p==a[0];
        if(m.getName().equals("hashCode"))return System.identityHashCode(p);
        if(m.getName().equals("toString"))return "fixture";
        throw new AssertionError("Unexpected method "+m);
    }
    static final Class<?>[] TYPES={DSICarVehicleStates.class,DSITrafficRegulation.class,DSICarDriverAssistance.class};
    static final int[][] ATTRIBUTES={{14,12},{3},{35,36,38,39,40,77,78,64}};
    static final class Item {
        final int type;
        final ServiceReference ref;
        final Object service;
        volatile boolean published=true,block,entered,releaseBlock;
        volatile DSIListener listener;
        volatile int gets,releases,registrations,unregisters;
        final List<Integer> sets=Collections.synchronizedList(new ArrayList<Integer>());
        final List<Integer> clears=Collections.synchronizedList(new ArrayList<Integer>());
        int failAttribute=-1;
        boolean failClear,replay;
        Item(final int type,final Object instance) {
            this.type=type;
            ref=(ServiceReference)proxy(ServiceReference.class,(p,m,a)-> {
                if(m.getName().equals("getProperty"))return DSIBase.DEVICE_INSTANCE.equals(a[0])?instance:null;
                return identity(p,m,a);
            });
            service=proxy(TYPES[type],(p,m,a)-> {
                String n=m.getName();
                if(n.equals("setNotification") || n.equals("clearNotification")) {
                    check(a.length==2 && a[0] instanceof Integer,"must subscribe narrowly, not all attributes");
                    int attr=(Integer)a[0];
                    check(Arrays.stream(ATTRIBUTES[type]).anyMatch(x->x==attr),"unexpected attribute");
                    check(a[1]==listener,"must use own listener, never an OEM listener");
                    if(n.equals("clearNotification")) {
                        clears.add(attr);
                        if(failClear)throw new IllegalStateException("injected clear failure");
                    } else {
                        sets.add(attr);
                        if(block) {
                            entered=true;
                            while(!releaseBlock)try{Thread.sleep(5);}catch(InterruptedException ignored){}
                        }
                        if(attr==failAttribute)throw new IllegalStateException("injected subscription failure");
                        if(replay && type==0 && attr==14)
                            ((DSICarVehicleStatesListener)listener).updateDynamicVehicleInfoHighFrequent(speed(0,0,1),1);
                    }
                    return null;
                }
                return identity(p,m,a); // Any setter/request/startDSIService is a test failure.
            });
        }
    }
    static final class Registry {
        final List<Item> items=new ArrayList<>();
        final IServiceManager manager;
        Registry() {
            manager=(IServiceManager)proxy(IServiceManager.class,(p,m,a)-> {
                switch(m.getName()) {
                case "getServiceReferences":
                    List<ServiceReference> refs=new ArrayList<>();
                    for(Item i:items)if(i.published && TYPES[i.type]==a[0])refs.add(i.ref);
                    return refs.toArray(new ServiceReference[0]);
                case "getService":
                    for(Item i:items)if(i.ref==a[0]){i.gets++;return i.service;}
                    throw new AssertionError("unknown reference");
                case "releaseService":
                    for(Item i:items)if(i.ref==a[0]){i.releases++;return null;}
                    throw new AssertionError("unknown release");
                case "registerDSIListener":
                    check(a[0].equals(0),"wrong listener instance");
                    for(Item i:items)if(i.published && a[1].equals(TYPES[i.type].getName()+"Listener")
                            && i.gets>i.releases) {
                        i.listener=(DSIListener)a[2];i.registrations++;
                        return proxy(ServiceRegistration.class,(q,method,args)-> {
                            if(method.getName().equals("unregister")){i.unregisters++;return null;}
                            return identity(q,method,args);
                        });
                    }
                    throw new AssertionError("registration not paired with instance0 service");
                case "unregisterService": ((ServiceRegistration)a[0]).unregister();return null;
                default:return identity(p,m,a);
                }
            });
        }
        Item add(int type,Object instance){Item i=new Item(type,instance);items.add(i);return i;}
        FrameworkRef framework() {
            IFrameworkAccess fw=(IFrameworkAccess)proxy(IFrameworkAccess.class,SpeedSourceDiagnosticsTest::identity);
            return new FrameworkRef((IContext)proxy(IContext.class,(p,m,a)-> {
                if(m.getName().equals("getFramework"))return fw;
                if(m.getName().equals("getServiceManager"))return manager;
                return identity(p,m,a);
            }));
        }
    }
    static DynamicVehicleInfoHighFrequent speed(float value,int unit,int validity) {
        DynamicVehicleInfoHighFrequent d=new DynamicVehicleInfoHighFrequent();
        d.vehicleSpeed=new CarBCSpeed();d.vehicleSpeed.speedValue=value;
        d.vehicleSpeed.speedUnit=unit;d.vehicleSpeed.speedValueState=validity;
        return d;
    }
    static String latest(SpeedSourceDiagnostics.Run run,int topic){return run.sample(topic,System.currentTimeMillis());}
    static void samplesAndLifecycle() throws Exception {
        Registry registry=new Registry();
        Item other=registry.add(0,1),unknown=registry.add(0,null),vehicle=registry.add(0,0);
        Item traffic=registry.add(1,"0"),tsd=registry.add(2,0);
        SpeedSourceDiagnostics.Run r=new SpeedSourceDiagnostics.Run(1);
        SpeedSourceDiagnostics.Binding[] b=new SpeedSourceDiagnostics.Binding[3];
        vehicle.replay=true;
        for(int i=0;i<3;i++){b[i]=new SpeedSourceDiagnostics.Binding(r,i);b[i].refresh(registry.manager);}
        check(other.gets==0 && unknown.gets==0,"wrong/unknown instance acquired");
        check(latest(r,0).contains("vehicle=0.0,0,1,usable=true"),"lost synchronous initial replay");
        for(Item i:new Item[]{vehicle,traffic,tsd}) {
            check(i.sets.size()==ATTRIBUTES[i.type].length,"wrong subscription set");
            b[i.type].refresh(registry.manager);check(i.gets==1,"duplicate subscription");
            check(i.listener.equals(i.listener) && !i.listener.equals(new Object()),"proxy identity contract");
        }
        DSICarVehicleStatesListener v=(DSICarVehicleStatesListener)vehicle.listener;
        DynamicVehicleInfoHighFrequent d=speed(31.5f,1,1);
        d.realVehicleSpeed=speed(30,1,1).vehicleSpeed;
        v.updateDynamicVehicleInfoHighFrequent(d,1);
        d.vehicleSpeed.speedValue=999;
        check(latest(r,0).contains("vehicle=31.5,1,1,usable=true real=30.0"),"did not copy mutable payload");
        for(DynamicVehicleInfoHighFrequent bad:new DynamicVehicleInfoHighFrequent[]{
                speed(Float.NaN,0,1),speed(Float.POSITIVE_INFINITY,0,1),speed(-1,0,1),
                speed(10,5,1),speed(10,0,0)}) {
            v.updateDynamicVehicleInfoHighFrequent(bad,1);
            check(latest(r,0).contains("usable=false"),"invalid speed accepted");
        }
        v.updateDynamicVehicleInfoHighFrequent(d,0);
        check(latest(r,0).endsWith("status=0 INVALID"),"invalid callback retained speed");
        v.updateDynamicVehicleInfoHighFrequent(null,1);
        check(latest(r,0).endsWith("status=1 NULL"),"null callback retained speed");
        String before=latest(r,0);
        set(CarPlayApp.class,"active",false);
        v.updateDynamicVehicleInfoHighFrequent(d,1);
        check(latest(r,0).split(" age_ms=")[0].equals(before.split(" age_ms=")[0]),"disconnected callback accepted");
        set(CarPlayApp.class,"active",true);
        DSITrafficRegulationListener t=(DSITrafficRegulationListener)traffic.listener;
        TrafficSignInformation info=new TrafficSignInformation();
        info.highestPrioritySpeedLimit=new SpeedLimitInfo(50,1,0);
        info.highestPrioritySign=2;info.trafficSignOneSource=3;
        info.additionalSignOne=9;info.variant=7;
        t.updateCurrentTrafficSign(info,1);
        check(latest(r,2).contains("limit=50,1,0 priority=2,0"),"limit/priority omitted");
        check(latest(r,2).contains("sources=3,0,0 additional=9"),"source/conditions omitted");
        info.highestPrioritySpeedLimit.speedLimitType=2;t.updateCurrentTrafficSign(info,1);
        check(latest(r,2).contains("limit=50,2,0"),"advisory distinction lost");
        info.highestPrioritySpeedLimit=null;t.updateCurrentTrafficSign(info,1);
        t.updateCurrentTrafficSign(null,0);
        check(latest(r,2).endsWith("status=0 INVALID"),"limit cache survived invalidation");
        DSICarDriverAssistanceListener a=(DSICarDriverAssistanceListener)tsd.listener;
        TSDSignFct sign=new TSDSignFct(){
            public String toString(){throw new AssertionError("must not serialize arbitrary DTO text");}
        };
        sign.sign=49;sign.signDynamicValue=50;sign.addSign=12;sign.addSignDynamicValue="PRIVATE_CONDITION";
        sign.signInfo=new TSDSignInfo();sign.signInfo.sourceIsCamera=true;sign.signInfo.signEffective=true;
        a.updateTSDSign1(sign,1);a.updateTSDSign2(sign,1);a.updateTSDSign3(sign,1);
        a.updateTSDSign4(sign,1);a.updateTSDSign5(sign,1);
        for(int topic=5;topic<=9;topic++) {
            String sample=latest(r,topic);
            check(sample.contains("effective=true") && sample.contains("camera=true"),"missing TSD applicability");
            check(!sample.contains("PRIVATE_CONDITION") && sample.contains("condition_text_present=true"),"text leaked");
        }
        sign.sign=0;sign.signInfo.signEffective=false;a.updateTSDSign1(sign,1);
        check(latest(r,5).contains("sign=0") && latest(r,5).contains("effective=false"),"no-sign transition lost");
        a.updateTSDSystemOnOff(false,1);a.updateTSDViewOptions(new TSDViewOptions(),1);
        a.updateTSDSystemMessages(new TSDSystemMessages(),1);
        check(latest(r,4).contains("on=false"),"disabled system lost");
        check(r.sample(5,0).contains("age_ms=-1"),"clock reversal became fresh");
        List<String> events=new ArrayList<>();
        for(String[] batch;(batch=r.drain()).length!=0;)events.addAll(Arrays.asList(batch));
        check(events.stream().anyMatch(s->s.contains("limit=50,1,0")),"inter-sample limit event missing");
        check(events.stream().anyMatch(s->s.contains("limit=NULL")),"inter-sample cancellation missing");
        check(events.stream().noneMatch(s->s.contains("PRIVATE_CONDITION")),"private text in events");
        for(int i=0;i<100;i++){sign.sign=i;a.updateTSDSign1(sign,1);}
        check(Arrays.toString(r.drain()).contains("dropped_transitions="),"queue overflow not explicit");
        a.asyncException(7,"PRIVATE_ERROR",12);
        check(latest(r,5).contains("ASYNC_ERROR code=7 request=12"),"async failure not invalidated");
        check(!latest(r,5).contains("PRIVATE_ERROR"),"raw error text leaked");
        a.updateTSDSign1(sign,1);check(latest(r,5).contains("ASYNC_ERROR"),"failed listener accepted later data");
        b[2].refresh(registry.manager);
        check(tsd.gets==2 && tsd.releases==1 && tsd.unregisters==1,"failed binding not replaced");
        a.updateTSDSign1(sign,1);check(latest(r,5).endsWith("NO_CALLBACK"),"old generation replay accepted");
        vehicle.published=false;b[0].refresh(registry.manager);
        check(latest(r,0).endsWith("NO_CALLBACK"),"removed service retained data");
        v.updateDynamicVehicleInfoHighFrequent(d,1);
        check(latest(r,0).endsWith("NO_CALLBACK"),"removed listener accepted data");
        Item replacement=registry.add(0,0);b[0].refresh(registry.manager);
        check(replacement.gets==1,"replacement not acquired");
        r.close();
        for(SpeedSourceDiagnostics.Binding binding:b)binding.close();
        check(traffic.releases==1 && traffic.unregisters==1 && tsd.releases==2,"unpaired service cleanup");
    }
    static void failureCleanup() {
        Registry registry=new Registry();Item item=registry.add(2,0);
        item.failAttribute=38;
        SpeedSourceDiagnostics.Run r=new SpeedSourceDiagnostics.Run(2);
        SpeedSourceDiagnostics.Binding b=new SpeedSourceDiagnostics.Binding(r,2);
        b.refresh(registry.manager);
        check(item.clears.equals(Arrays.asList(35,36,38)),"partial subscribe cleanup omitted attempted attribute");
        check(item.releases==1 && item.unregisters==1 && b.capture==null,"failed registration leaked");
        item.failAttribute=-1;b.refresh(registry.manager);item.failClear=true;b.close();
        check(item.releases==2 && item.unregisters==2,"clear failure blocked remaining cleanup");
        check(item.clears.size()==11,"did not attempt every clear after failure");
    }
    interface Condition { boolean ready(); }
    static void await(Condition condition,String reason) throws Exception {
        long end=System.currentTimeMillis()+7000;
        while(!condition.ready() && System.currentTimeMillis()<end)Thread.sleep(10);
        check(condition.ready(),reason);
    }
    static void workerGates() throws Exception {
        Registry registry=new Registry();Item v=registry.add(0,0),t=registry.add(1,0),a=registry.add(2,0);
        set(SettingsRuntime.class,"sessionKnown",true);set(SettingsRuntime.class,"sessionEnabled",true);
        set(SettingsRuntime.class,"sessionVerbose",false);
        SpeedSourceDiagnostics module=new SpeedSourceDiagnostics();
        module.start(registry.framework());Thread.sleep(1100);
        check(v.gets==0 && t.gets==0 && a.gets==0,"normal mode acquired services");
        set(SettingsRuntime.class,"sessionVerbose",true);
        await(()->a.sets.size()==8,"verbose session failed to subscribe");
        set(SettingsRuntime.class,"sessionKnown",false);
        await(()->a.unregisters==1,"unknown session failed to unsubscribe");
        check(v.releases==1 && t.releases==1,"session shutdown incomplete");
        module.stop();
        Field worker=SpeedSourceDiagnostics.class.getDeclaredField("worker");worker.setAccessible(true);
        ((Thread)worker.get(module)).join(2000);
        check(!((Thread)worker.get(module)).isAlive(),"diagnostic worker leaked");
        set(SettingsRuntime.class,"sessionKnown",true);
        v.block=true;
        module.start(registry.framework());
        await(()->v.entered,"fixture did not block notification");
        long start=System.currentTimeMillis();module.stop();
        check(System.currentTimeMillis()-start<250,"stop waited on external DSI call");
        check(!module.start(registry.framework()),"started concurrent worker while old DSI call blocked");
        v.releaseBlock=true;
        ((Thread)worker.get(module)).join(2000);
        check(!((Thread)worker.get(module)).isAlive(),"blocked worker did not finish cleanup");
        check(v.gets==v.releases && t.gets==t.releases && a.gets==a.releases,"blocked shutdown leaked handles");
    }
    public static void main(String[] args) throws Exception {
        Log.setLevel(Log.E);
        set(CarPlayApp.class,"active",true);
        samplesAndLifecycle();failureCleanup();workerGates();
        set(CarPlayApp.class,"active",false);
        System.out.println("SpeedSourceDiagnosticsTest PASS: exact-stock passive subscriptions, replay, validity, privacy, bounded transitions, service generations, opt-in and nonblocking teardown");
    }
}
