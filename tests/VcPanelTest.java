package com.luka.carplay.settings;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.ScreenModule;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;

public final class VcPanelTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static Field field(Class<?> type,String name)throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
    }
    static void set(String name,Object value)throws Exception {
        synchronized(field(VcPanel.class,"LOCK").get(null)){field(VcPanel.class,name).set(null,value);}
    }
    static Object get(String name)throws Exception {
        synchronized(field(VcPanel.class,"LOCK").get(null)){return field(VcPanel.class,name).get(null);}
    }
    static void stockField(Object object,String name,Object value)throws Exception {
        Class<?> type=object.getClass();
        while(type!=null) {
            try {field(type,name).set(object,value);return;}
            catch(NoSuchFieldException e){type=type.getSuperclass();}
        }
        throw new NoSuchFieldException(name);
    }
    static void acknowledge()throws Exception {
        set("ackUntil",System.currentTimeMillis()+300L);set("shownRevision",get("revision"));
    }
    static void hold(){VcPanel.roller(1);VcPanel.roller(3);VcPanel.roller(4);VcPanel.roller(0);}
    public static void main(String[] args)throws Exception {
        check(new File("/.dockerenv").exists(),"Docker-only fixture");
        Path root=Files.createTempDirectory("vc-panel-");
        Constructor<Preferences> ctor=Preferences.class.getDeclaredConstructor(File.class,File.class);ctor.setAccessible(true);
        Preferences prefs=ctor.newInstance(root.resolve("preferences").toFile(),root.resolve("layout").toFile());
        field(Preferences.class,"instance").set(null,prefs);prefs.set(Setting.ENABLED,1);
        field(ScreenModule.class,"platformSupported").set(null,true);
        field(ScreenModule.class,"connected").set(null,true);
        field(ScreenModule.class,"altScreenVideo").set(null,true);
        Field connection=field(CarplayBus.class,"connectionGeneration");
        connection.set(CarplayBus.getInstance(),2);
        field(CarplayBus.class,"running").set(CarplayBus.getInstance(),true);
        field(CarplayBus.class,"out").set(CarplayBus.getInstance(),new DataOutputStream(new ByteArrayOutputStream()));
        set("running",true);set("pid",42);VcPanel.presentation(true,false,false);
        hold();check(!(Boolean)get("open"),"opened without map-focus evidence");
        check(!VcPanel.scale(1),"closed router consumes scale");
        VcPanel.roller(1);
        check(!(Boolean)get("open"),"press opened before hold");
        VcPanel.roller(3);check((Boolean)get("open"),"hold did not open");
        VcPanel.roller(4);VcPanel.roller(5);VcPanel.roller(0);
        check((Integer)get("focus")==0 && (Boolean)get("open"),"opening gesture selected or closed");
        check(!VcPanel.scale(-1),"opening without frame acknowledgement consumed scale");
        Method snapshot=VcPanel.class.getDeclaredMethod("snapshot",long.class);snapshot.setAccessible(true);
        Files.write(Paths.get(args[0]),((String)snapshot.invoke(null,1000L)).getBytes("US-ASCII"));
        field(SettingsRuntime.class,"result").set(null,"Saved: fixture");
        field(SettingsRuntime.class,"savedAt").set(null,System.currentTimeMillis());
        check(((String)snapshot.invoke(null,1000L)).contains("Saved; CarPlay reconnect pending"),
            "save notice concealed reconnect requirement");
        field(SettingsRuntime.class,"result").set(null,"Ready");
        acknowledge();
        Object before=get("revision");
        check(VcPanel.scale(0) && before.equals(get("revision")),"zero scale manufactured a menu step");
        Class<?> unsafeType=Class.forName("sun.misc.Unsafe");
        Object unsafe=field(unsafeType,"theUnsafe").get(null);
        Class<?> listenerType=Class.forName("de.audi.tghu.navi.app.cluster.ScreenCombiBAPListener");
        Object listener=unsafeType.getMethod("allocateInstance",Class.class).invoke(unsafe,listenerType);
        Class<?> service=Class.forName("de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi");
        final int[] replies={0};
        stockField(listener,"combiservice",Proxy.newProxyInstance(VcPanelTest.class.getClassLoader(),
            new Class[]{service},(p,m,a)->{if(m.getName().equals("updateMapScale"))replies[0]++;return null;}));
        Class<?> distance=Class.forName("de.audi.tghu.navi.app.cluster.BAPDistanceFormatter$BAPDistance");
        stockField(listener,"mapScale",distance.getMethod("invalid").invoke(null));
        acknowledge();
        listenerType.getMethod("setMapScale",int.class).invoke(listener,1);
        check(replies[0]==1 && (Integer)get("focus")==1,"menu scale lost stock status reply");
        // No MapManager was supplied: any accidental stock zoom delegation would fail above.
        VcPanel.scale(-1);acknowledge();
        check((Integer)get("focus")==0,"negative BAP step did not move up");
        check(VcPanel.scale(3) && (Integer)get("focus")==3,"positive BAP detents did not move down once");
        VcPanel.roller(1);VcPanel.roller(0);
        check(get("page").equals("root"),"selected unseen revision");
        acknowledge();VcPanel.roller(1);VcPanel.roller(0);
        check(get("page").equals("choice:"+Setting.MASCOT),"mascot submenu missing");
        com.luka.carplay.core.SteeringWheelInputModule module=new com.luka.carplay.core.SteeringWheelInputModule();
        field(module.getClass(),"running").set(module,true);
        org.dsi.ifc.keypanel.DSIKeyPanelListener keys=
            (org.dsi.ifc.keypanel.DSIKeyPanelListener)field(module.getClass(),"listener").get(module);
        acknowledge();keys.updateKey2(4,41,1,1,1);
        check(com.luka.carplay.core.SteeringWheelInputModule.consumePanelBack(
            de.audi.app.terminalmode.keyevents.KeyState.PRESSED),"panel Back leaked to main CarPlay");
        keys.updateKey2(4,41,0,2,1);
        check(com.luka.carplay.core.SteeringWheelInputModule.consumePanelBack(
            de.audi.app.terminalmode.keyevents.KeyState.RELEASED),"panel Back release leaked");
        check(get("page").equals("root") && (Integer)get("focus")==3,"Back lost parent focus");
        acknowledge();keys.updateKey2(4,41,1,3,1);keys.updateKey2(1,16,1,4,1);
        check(!com.luka.carplay.core.SteeringWheelInputModule.consumePanelBack(
            de.audi.app.terminalmode.keyevents.KeyState.PRESSED),"console event retained wheel Back marker");
        keys.updateKey2(4,41,0,5,1);
        com.luka.carplay.core.SteeringWheelInputModule.consumePanelBack(de.audi.app.terminalmode.keyevents.KeyState.RELEASED);
        VcPanel.presentation(false,false,false);check(!(Boolean)get("open"),"View change did not close");
        hold();check(!(Boolean)get("open"),"small View opened unmeasured panel");
        VcPanel.presentation(true,false,false);VcPanel.scale(1);hold();acknowledge();
        set("ackUntil",System.currentTimeMillis()-1);
        check(!VcPanel.scale(1) && (Boolean)get("open"),"expired cache must reject input pending worker status read");
        VcPanel.roller(1);VcPanel.roller(0);
        check(get("page").equals("root"),"expired cache accepted a selection");
        check(!VcPanel.back() && !(Boolean)get("open"),"unacknowledged Back captured input instead of dismissing");
        VcPanel.scale(1);hold();acknowledge();VcPanel.dismiss();
        hold();check(!(Boolean)get("open"),"drawer/tab change retained focus evidence");
        VcPanel.scale(1);hold();acknowledge();
        connection.set(CarplayBus.getInstance(),3);
        check(!VcPanel.scale(1) && !(Boolean)get("open"),"old receiver retained input");
        VcPanel.roller(1);connection.set(CarplayBus.getInstance(),4);
        check(!VcPanel.roller(0),"old press toggled new receiver");
        check(VcPanel.parseStatus("VCPANEL1 42 7 3 1500 2 1\n")[5]==1,"status contract");
        try {VcPanel.parseStatus("VCPANEL1 42 7 3 1500 2 1 junk");throw new AssertionError("extra status fields");}
        catch(IOException expected){}
        VcPanel.scale(1);hold();acknowledge();
        field(com.luka.carplay.pdc.PdcSmallStageGuard.class,"parkingControlsActive").set(null,true);
        check(!VcPanel.scale(1) && !(Boolean)get("open"),"parking/camera retained panel input");
        field(com.luka.carplay.pdc.PdcSmallStageGuard.class,"parkingControlsActive").set(null,false);
        check(!VcPanel.roller(1) && VcPanel.roller(0),"ordinary short gesture not deferred until release");
        VcPanel.scale(1);hold();acknowledge();VcPanel.scale(3);acknowledge();
        VcPanel.roller(1);VcPanel.roller(0);acknowledge();VcPanel.scale(3);acknowledge();
        check(((String)snapshot.invoke(null,1000L)).contains("Capybara"),"Capybara missing from VC choices");
        Path helper=Paths.get("/mnt/app/root/carplay-altscreen/bin/carplay_mmi_action.sh");
        check(!Files.exists(helper),"fixture helper already exists");
        Files.createDirectories(helper.getParent());Files.write(helper,"#!/bin/sh\nexit 0\n".getBytes("US-ASCII"));
        VcPanel.roller(1);VcPanel.roller(0);
        long end=System.currentTimeMillis()+4000;
        while(prefs.snapshot().get(Setting.MASCOT)!=3 && System.currentTimeMillis()<end)Thread.sleep(10);
        check(prefs.snapshot().get(Setting.MASCOT)==3,"Capybara not persisted by shared settings worker");
        SettingsRuntime.stop();VcPanel.stop();Files.delete(helper);
        Files.deleteIfExists(root.resolve("preferences"));Files.deleteIfExists(root.resolve("layout"));Files.delete(root);
        System.out.println("VC panel: default hold, short/release arbitration, ack/revision leases, scale, Back, View/drawer/session/parking gates and shared save PASS");
    }
}
