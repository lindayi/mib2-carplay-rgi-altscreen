package com.luka.carplay.rgd;

import com.luka.carplay.settings.Preferences;
import com.luka.carplay.settings.Setting;
import com.luka.carplay.framework.Log;
import java.io.File;
import java.lang.reflect.*;
import java.nio.file.*;

/** Real worker and preference notifications; no vehicle filesystem or clock sleeps. */
public final class InfoPreferencesTest {
    static void check(boolean ok,String why){RgiDeliveryRecoveryTest.check(ok,why);}
    static Object get(Object o,String f)throws Exception{return RgiDeliveryRecoveryTest.get(o,f);}
    static void set(Object o,String f,Object v)throws Exception{RgiDeliveryRecoveryTest.set(o,f,v);}
    static void call(Object o,String name)throws Exception{RgiDeliveryRecoveryTest.method(o.getClass(),name).invoke(o);}
    static void waitFor(CurrentPositionDeliveryTest.Fixture f,int phase)throws Exception {
        long end=System.nanoTime()+2000000000L;
        while(System.nanoTime()<end) {
            synchronized(f.rg) {
                synchronized(get(f.rg,"presentationLock")) {
                    if((Integer)get(f.bridge,"infoPhase")==phase
                        && !(Boolean)get(f.rg,"infoPresentationRefreshPending")
                        && f.out.text.startsWith("\u25cc ")==(phase==1))return;
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("information phase "+phase+" not delivered: "+f.out.text);
    }
    public static void main(String[] args)throws Exception {
        Log.setLevel(-1);
        Path root=Files.createTempDirectory("info-preferences-");
        Constructor<Preferences> ctor=Preferences.class.getDeclaredConstructor(File.class,File.class);
        ctor.setAccessible(true);
        Preferences prefs=ctor.newInstance(root.resolve("preferences").toFile(),root.resolve("legacy").toFile());
        Field singleton=Preferences.class.getDeclaredField("instance");singleton.setAccessible(true);
        Object previous=singleton.get(null);singleton.set(null,prefs);
        CurrentPositionDeliveryTest.Fixture f=new CurrentPositionDeliveryTest.Fixture();
        Object lock=get(f.rg,"presentationLock");
        Preferences.Listener listener=(Preferences.Listener)get(f.rg,"infoPreferencesListener");
        prefs.addListener(listener);
        f.feed("time_remaining_seconds:n:600\ncurrent_road:s:Current Street\n");
        CurrentPositionDeliveryTest.Worker worker=new CurrentPositionDeliveryTest.Worker(f);
        worker.start();
        try {
            prefs.set(Setting.INFO_DEFAULT,1);waitFor(f,1);
            synchronized(lock){check((Long)get(f.rg,"infoReturnDeadline")==0,"default Trip acquired timer");}
            long before=System.currentTimeMillis();
            call(f.rg,"requestInfoModeToggle");waitFor(f,0);
            synchronized(lock) {
                check((Long)get(f.rg,"infoReturnDeadline")>=before+20000,"Road override lacks 20s hold");
                set(f.rg,"infoReturnDeadline",System.currentTimeMillis()+100);
                set(f.rg,"presentationWake",true);lock.notifyAll();
            }
            waitFor(f,1);
            prefs.set(Setting.INFO_RETURN,1);
            call(f.rg,"requestInfoModeToggle");waitFor(f,0);
            int renderer=f.renderer.writes;
            prefs.set(Setting.INFO_ROAD,1);
            long end=System.nanoTime()+2000000000L;
            while(!"Current Street".equals(f.out.text) && System.nanoTime()<end)Thread.sleep(5);
            check("Current Street".equals(f.out.text),"live current-road preference not published");
            check(f.renderer.writes==renderer,"information text redrew maneuver");
            RgiDeliveryRecoveryTest.method(RouteGuidance.class,"requestViewAreaRefresh",int.class).invoke(f.rg,1);
            waitFor(f,0);
            synchronized(lock){check((Long)get(f.rg,"infoReturnDeadline")==0,"pinned Road timed out");}
            prefs.set(Setting.INFO_RETURN,0);waitFor(f,0);
            synchronized(lock){check((Long)get(f.rg,"infoReturnDeadline")>System.currentTimeMillis(),"live timer enable");}
            prefs.set(Setting.INFO_RETURN,1);waitFor(f,0);
            synchronized(lock){check((Long)get(f.rg,"infoReturnDeadline")==0,"live timer disable");}
            synchronized(f.rg){f.feed("route_generation:n:101\nroute_state:n:1\ntime_remaining_seconds:n:600\n");}
            waitFor(f,1);
            prefs.set(Setting.INFO_DEFAULT,0);waitFor(f,0);
            call(f.rg,"requestInfoModeToggle");waitFor(f,1);
            RgiDeliveryRecoveryTest.method(RouteGuidance.class,"requestViewAreaRefresh",int.class).invoke(f.rg,0);
            waitFor(f,1);
            synchronized(f.rg){f.feed("route_state:n:0\nsource_supports_rg:n:0\n");}
            synchronized(lock) {
                check((Integer)get(f.rg,"desiredInfoPhase")==0 && (Long)get(f.rg,"infoReturnDeadline")==0,
                    "route end retained manual page");
            }
            prefs.set(Setting.INFO_DEFAULT,1);
            set(f.bridge,"initialized",true);set(f.bridge,"nativeStopAttempted",true);
            set(f.bridge,"nativeStopWasRouteAbsent",false);
            check(f.bridge.onStart(),"BAP route startup fixture");
            check((Integer)get(f.bridge,"infoPhase")==1,"first route publication ignores Trip default");
        } finally {
            worker.finish();prefs.removeListener(listener);f.bridge.onShutdown();singleton.set(null,previous);
            Files.deleteIfExists(root.resolve("preferences"));Files.delete(root);
        }
        System.out.println("InfoPreferencesTest: both defaults, 20s return, pinned View, current-road isolation, live changes, route reset and first publication PASS");
    }
}
