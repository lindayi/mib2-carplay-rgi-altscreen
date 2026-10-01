package com.luka.carplay.core;

import com.luka.carplay.framework.Log;
import com.luka.carplay.settings.SettingsRuntime;
import de.audi.app.terminalmode.osgi.IServiceManager;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.dsi.ifc.base.DSIBase;
import org.dsi.ifc.base.DSIListener;
import org.dsi.ifc.carvehiclestates.*;
import org.dsi.ifc.cardriverassistance.*;
import org.dsi.ifc.global.CarBCSpeed;
import org.dsi.ifc.global.CarViewOption;
import org.dsi.ifc.trafficregulation.*;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

/** Opt-in measurement only; never selects a road limit or changes an Audi setting. */
public final class SpeedSourceDiagnostics implements Module {
    private static final String TAG="SpeedProbe";
    private static volatile String state="OFF";
    private Thread worker;
    private Run run;
    private int generation;

    public String name(){return "speed-source-diagnostics";}
    public static String status(){return CarPlayApp.isActive()?state:"OFF";}

    public synchronized boolean start(final FrameworkRef fw) {
        if(run!=null && run.active)return true;
        if(worker!=null && worker.isAlive())return false;
        final Run next=new Run(++generation);
        run=next;
        worker=new Thread(new Runnable(){public void run(){loop(next,fw);}},"carplay-speed-probe");
        worker.setDaemon(true);
        try {worker.start();}
        catch(RuntimeException e){next.active=false;state="WORKER_ERROR";Log.e(TAG,state,e);}
        return true; // An optional diagnostic must not hold up the cockpit modules.
    }

    public synchronized void stop() {
        if(run!=null)run.close();
        state="OFF";
        if(worker!=null)worker.interrupt();
    }

    private static void loop(Run r,FrameworkRef fw) {
        Binding[] bindings=new Binding[]{new Binding(r,0),new Binding(r,1),new Binding(r,2)};
        long nextPoll=0,nextSample=0,nextHeartbeat=0;
        boolean enabled=false;
        try {
            while(r.active) {
                boolean wanted=CarPlayApp.isActive() && SettingsRuntime.verboseDiagnosticSession();
                long now=System.currentTimeMillis();
                if(wanted!=enabled) {
                    enabled=wanted;
                    if(!enabled) {
                        for(int i=0;i<bindings.length;i++)bindings[i].close();
                        r.clear();
                        state="OFF";
                    } else {
                        Log.i(TAG,"START run="+r.id+" build="+CarPlayApp.BUILD_ID
                            +" passive=1 units=0_kmh_1_mph no_positions=1");
                        nextPoll=nextSample=nextHeartbeat=0;
                    }
                }
                if(enabled) {
                    if(now>=nextPoll || nextPoll-now>5000L) {
                        int bound=0;
                        for(int i=0;i<bindings.length;i++) {
                            bindings[i].refresh(fw.serviceManager());
                            if(bindings[i].capture!=null)bound++;
                        }
                        now=System.currentTimeMillis();
                        if(r.active)state="Subscribed "+bound+"/3 (not data proof)";
                        nextPoll=now+5000L;
                    }
                    String[] events=r.drain();
                    for(int i=0;i<events.length;i++)Log.i(TAG,events[i]);
                    if(now>=nextSample || nextSample-now>2000L) {
                        Log.i(TAG,r.sample(0,now));
                        nextSample=now+2000L;
                    }
                    if(now>=nextHeartbeat || nextHeartbeat-now>10000L) {
                        for(int i=1;i<TOPICS.length;i++)Log.i(TAG,r.sample(i,now));
                        nextHeartbeat=now+10000L;
                    }
                }
                try {Thread.sleep(1000L);}
                catch(InterruptedException e){if(!r.active)break;}
            }
        } catch(RuntimeException e){state="PROBE_ERROR";Log.e(TAG,state,e);}
        catch(LinkageError e){state="LINKAGE_ERROR";Log.e(TAG,state,e);}
        finally {
            r.close();
            for(int i=0;i<bindings.length;i++)bindings[i].close();
            if(enabled)Log.i(TAG,"STOP run="+r.id);
        }
    }

    static final String[] TOPICS={"speed","speed_view","limit","tsd_view","tsd_on",
        "tsd_1","tsd_2","tsd_3","tsd_4","tsd_5","tsd_messages"};
    private static final Class[] SERVICES={DSICarVehicleStates.class,DSITrafficRegulation.class,
        DSICarDriverAssistance.class};
    private static final Class[] LISTENERS={DSICarVehicleStatesListener.class,
        DSITrafficRegulationListener.class,DSICarDriverAssistanceListener.class};
    private static final int[][] ATTRS={
        {DSICarVehicleStates.ATTR_DYNAMICVEHICLEINFOHIGHFREQUENT,
         DSICarVehicleStates.ATTR_DYNAMICVEHICLEINFOHIGHFREQUENTVIEWOPTIONS},
        {DSITrafficRegulation.ATTR_CURRENTTRAFFICSIGN},
        {DSICarDriverAssistance.ATTR_TSDVIEWOPTIONS,DSICarDriverAssistance.ATTR_TSDSYSTEMONOFF,
         DSICarDriverAssistance.ATTR_TSDSIGN1,DSICarDriverAssistance.ATTR_TSDSIGN2,
         DSICarDriverAssistance.ATTR_TSDSIGN3,DSICarDriverAssistance.ATTR_TSDSIGN4,
         DSICarDriverAssistance.ATTR_TSDSIGN5,DSICarDriverAssistance.ATTR_TSDSYSTEMMESSAGES}};
    private static final int[][] TOPIC_IDS={{0,1},{2},{3,4,5,6,7,8,9,10}};
    private static final String[][] METHODS={
        {"updateDynamicVehicleInfoHighFrequent","updateDynamicVehicleInfoHighFrequentViewOptions"},
        {"updateCurrentTrafficSign"},
        {"updateTSDViewOptions","updateTSDSystemOnOff","updateTSDSign1","updateTSDSign2",
         "updateTSDSign3","updateTSDSign4","updateTSDSign5","updateTSDSystemMessages"}};

    static final class Run {
        final int id;
        volatile boolean active=true;
        private final String[] latest=new String[TOPICS.length];
        private final long[] counts=new long[TOPICS.length],invalid=new long[TOPICS.length],
            times=new long[TOPICS.length];
        private final int[] epochs=new int[TOPICS.length];
        private final String[] queue=new String[64];
        private int head,size;
        private long dropped;
        Run(int id){this.id=id;}
        synchronized void close(){active=false;}
        synchronized void clear() {
            for(int i=0;i<latest.length;i++){latest[i]=null;counts[i]=invalid[i]=times[i]=0;}
            for(int i=0;i<queue.length;i++)queue[i]=null;
            head=size=0;dropped=0;
        }
        synchronized void reset(int source,int epoch) {
            int[] topics=TOPIC_IDS[source];
            for(int i=0;i<topics.length;i++) {
                int t=topics[i];latest[t]=null;counts[t]=invalid[t]=times[t]=0;epochs[t]=epoch;
            }
        }
        synchronized void record(Capture c,int topic,String value,boolean valid,long now) {
            if(!active || !c.active || !CarPlayApp.isActive())return;
            boolean changed=!value.equals(latest[topic]);
            latest[topic]=value;counts[topic]++;times[topic]=now;
            if(!valid)invalid[topic]++;
            // Speed is sampled; preserve bounded sign/validity transitions between samples.
            if(topic!=0 && changed)enqueue("EVENT "+sample(topic,now));
        }
        private void enqueue(String text) {
            if(size==queue.length){queue[head]=null;head=(head+1)%queue.length;size--;dropped++;}
            queue[(head+size)%queue.length]=text;size++;
        }
        synchronized String[] drain() {
            int n=Math.min(size,16);
            String[] result=new String[n+(dropped==0?0:1)];
            for(int i=0;i<n;i++){result[i]=queue[head];queue[head]=null;head=(head+1)%queue.length;size--;}
            if(dropped!=0){result[n]="run="+id+" dropped_transitions="+dropped;dropped=0;}
            return result;
        }
        synchronized String sample(int topic,long now) {
            long age=counts[topic]==0 || now<times[topic]?-1:now-times[topic];
            return "run="+id+" epoch="+epochs[topic]+" topic="+TOPICS[topic]+" count="+counts[topic]
                +" invalid="+invalid[topic]+" at_ms="+times[topic]+" age_ms="+age+" "
                +(latest[topic]==null?"NO_CALLBACK":latest[topic]);
        }
    }

    static final class Binding {
        final Run run;
        final int source;
        private IServiceManager manager;
        private ServiceReference reference;
        private DSIBase service;
        private ServiceRegistration registration;
        Capture capture;
        private DSIListener listener;
        private int attempted,epoch;
        private boolean acquired;
        private String lastState="";
        Binding(Run run,int source){this.run=run;this.source=source;}
        private void report(String value) {
            if(!value.equals(lastState)) {
                lastState=value;
                Log.i(TAG,"run="+run.id+" source="+source+" epoch="+epoch+" "+value);
            }
        }
        void refresh(IServiceManager sm) {
            if(!run.active)return;
            try {
                ServiceReference chosen=null;
                ServiceReference[] refs=sm==null?null:sm.getServiceReferences(SERVICES[source]);
                if(refs!=null)for(int i=0;i<refs.length;i++) {
                    Object instance=refs[i].getProperty(DSIBase.DEVICE_INSTANCE);
                    if(!"0".equals(instance)
                            && !(instance instanceof Integer && ((Integer)instance).intValue()==0))continue;
                    if(chosen==null)chosen=refs[i];
                    if(refs[i].equals(reference)){chosen=refs[i];break;}
                }
                if(chosen!=null && chosen.equals(reference) && capture!=null && !capture.failed)return;
                close();
                if(chosen==null){report("MISSING_INSTANCE_0");return;}
                manager=sm;reference=chosen;
                Object found=sm.getService(chosen);
                acquired=found!=null;
                if(!SERVICES[source].isInstance(found)){close();report("SERVICE_UNAVAILABLE");return;}
                service=(DSIBase)found;
                capture=new Capture(run,source);
                run.reset(source,++epoch);
                // OEM adapters require a Car application. A Foundation/J9 proxy instead
                // implements only the listener contract, without constructing an OEM component.
                listener=(DSIListener)Proxy.newProxyInstance(LISTENERS[source].getClassLoader(),
                    new Class[]{LISTENERS[source]},capture);
                registration=sm.registerDSIListener(0,LISTENERS[source].getName(),listener);
                if(registration==null)throw new IllegalStateException("No listener registration");
                for(int i=0;i<ATTRS[source].length && run.active;i++) {
                    attempted=i+1;
                    service.setNotification(ATTRS[source][i],listener);
                }
                if(!run.active){close();return;}
                report("SUBSCRIBED");
            } catch(RuntimeException e){close();report("SUBSCRIBE_ERROR "+e.getClass().getName());}
            catch(LinkageError e){close();report("LINKAGE_ERROR "+e.getClass().getName());}
        }
        void close() {
            if(capture!=null)capture.active=false;
            for(int i=0;i<attempted;i++) {
                try {service.clearNotification(ATTRS[source][i],listener);}
                catch(RuntimeException e){Log.w(TAG,"unsubscribe source="+source+" attr="+ATTRS[source][i],e);}
                catch(LinkageError e){Log.w(TAG,"unsubscribe linkage source="+source,e);}
            }
            if(registration!=null) {
                try {manager.unregisterService(registration);}
                catch(RuntimeException e){Log.w(TAG,"unregister source="+source,e);}
                catch(LinkageError e){Log.w(TAG,"unregister linkage source="+source,e);}
            }
            if(reference!=null && acquired) {
                try {manager.releaseService(reference);}
                catch(RuntimeException e){Log.w(TAG,"release source="+source,e);}
                catch(LinkageError e){Log.w(TAG,"release linkage source="+source,e);}
            }
            if(capture!=null){run.reset(source,epoch);report("UNSUBSCRIBED");}
            manager=null;reference=null;service=null;registration=null;capture=null;listener=null;
            attempted=0;acquired=false;
        }
    }

    static final class Capture implements InvocationHandler {
        final Run run;
        final int source;
        volatile boolean active=true,failed;
        Capture(Run run,int source){this.run=run;this.source=source;}
        public Object invoke(Object proxy,Method method,Object[] args) {
            String name=method.getName();
            if(name.equals("equals"))return Boolean.valueOf(proxy==args[0]);
            if(name.equals("hashCode"))return new Integer(System.identityHashCode(proxy));
            if(name.equals("toString"))return "CarplaySpeedProbeListener-"+source;
            if(!active || failed || !run.active || !CarPlayApp.isActive())return null;
            long now=System.currentTimeMillis();
            if(name.equals("asyncException")) {
                synchronized(run) {
                    for(int i=0;i<TOPIC_IDS[source].length;i++)run.record(this,TOPIC_IDS[source][i],
                        "ASYNC_ERROR code="+args[0]+" request="+args[2],false,now);
                    failed=true;
                }
                return null; // Deliberately exclude the arbitrary DSI error string.
            }
            for(int i=0;i<METHODS[source].length;i++)if(name.equals(METHODS[source][i])) {
                int topic=TOPIC_IDS[source][i],status=((Integer)args[1]).intValue();
                String value=status!=1?"status="+status+" INVALID":
                    "status=1 "+describe(topic,args[0]);
                run.record(this,topic,value,status==1 && args[0]!=null,now);
                break;
            }
            return null;
        }
    }

    static String describe(int topic,Object object) {
        if(object==null)return "NULL";
        if(topic==0) {
            DynamicVehicleInfoHighFrequent d=(DynamicVehicleInfoHighFrequent)object;
            return "vehicle="+speed(d.vehicleSpeed)+" real="+speed(d.realVehicleSpeed);
        }
        if(topic==1) {
            DynamicVehicleInfoHighFrequentViewOptions d=(DynamicVehicleInfoHighFrequentViewOptions)object;
            return "vehicle="+view(d.vehicleSpeed)+" real="+view(d.realVehicleSpeed);
        }
        if(topic==2) {
            TrafficSignInformation d=(TrafficSignInformation)object;
            SpeedLimitInfo s=d.highestPrioritySpeedLimit;
            return "limit="+(s==null?"NULL":s.speedLimit+","+s.speedLimitType+","+s.speedUnit)
                +" priority="+d.highestPrioritySign+","+d.secondHighestPrioritySign
                +" signs="+d.trafficSignOne+","+d.trafficSignTwo+","+d.trafficSignThree
                +" sources="+d.trafficSignOneSource+","+d.trafficSignTwoSource+","+d.trafficSignThreeSource
                +" additional="+d.additionalSignOne+","+d.additionalSignTwo+","+d.additionalSignThree
                +" warnings="+d.warningSignOne+","+d.warningSignTwo+","+d.warningSignThree
                +" variant="+d.variant+" info_enum="+d.informationText;
        }
        if(topic==3) {
            TSDViewOptions d=(TSDViewOptions)object;
            return "type="+(d.configuration==null?-1:d.configuration.type)+" sign="+view(d.roadSign)
                +" on="+view(d.systemOnOff)+" messages="+view(d.systemMessages);
        }
        if(topic==4)return "on="+object;
        if(topic>=5 && topic<=9) {
            TSDSignFct d=(TSDSignFct)object;TSDSignInfo s=d.signInfo;
            return "sign="+d.sign+" value="+d.signDynamicValue+" additional="+d.addSign
                +" condition_text_present="+(d.addSignDynamicValue!=null && d.addSignDynamicValue.length()!=0)
                +(s==null?" flags=NULL":" effective="+s.signEffective+" mph="+s.mph
                +" camera="+s.sourceIsCamera+" database="+s.sourceIsDatabase+" fusion="+s.sourceIsFusion
                +" roadwork="+s.roadWorkSign+" warning="+s.signWarning
                +" urban="+s.startUrbanArea+","+s.endUrbanArea
                +" calmed="+s.startTrafficCalmedArea+","+s.endTrafficCalmedArea);
        }
        TSDSystemMessages d=(TSDSystemMessages)object;
        return "camera_blind="+d.cameraBlind+" navigation="+d.navigationData+" system="+d.system
            +" area="+d.operationArea+" init="+d.roadSignRecognitionInit+" recognition="+d.roadSignRecognition;
    }
    private static String speed(CarBCSpeed d) {
        if(d==null)return "NULL";
        boolean usable=d.speedValueState==1 && (d.speedUnit==0 || d.speedUnit==1)
            && !Float.isNaN(d.speedValue) && !Float.isInfinite(d.speedValue) && d.speedValue>=0;
        return d.speedValue+","+d.speedUnit+","+d.speedValueState+",usable="+usable;
    }
    private static String view(CarViewOption d){return d==null?"NULL":d.state+","+d.reason;}
}
