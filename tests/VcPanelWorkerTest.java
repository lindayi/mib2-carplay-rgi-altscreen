package com.luka.carplay.settings;

import static com.luka.carplay.settings.VcPanelTest.*;
import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.ScreenModule;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;

/** Real Java worker/atomic files, with an explicitly simulated renderer acknowledgement. */
public final class VcPanelWorkerTest {
    static String[] control()throws Exception {
        return new String(Files.readAllBytes(Paths.get(VcPanel.CONTROL)),"US-ASCII").split("\n")[0].split(" ");
    }
    static void acknowledgeFile(String[] header,int process)throws Exception {
        Path temporary=Paths.get(VcPanel.STATUS+".test");
        Files.write(temporary,("VCPANEL1 "+process+" "+header[2]+" "+header[3]+" "+
            (System.currentTimeMillis()+300)+" "+header[5]+" 1\n").getBytes("US-ASCII"));
        Files.move(temporary,Paths.get(VcPanel.STATUS),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    public static void main(String[] args)throws Exception {
        check(new File("/.dockerenv").exists(),"Docker-only fixture");
        Path root=Files.createTempDirectory("vc-worker-");
        Constructor<Preferences> constructor=Preferences.class.getDeclaredConstructor(File.class,File.class);
        constructor.setAccessible(true);
        Preferences prefs=constructor.newInstance(root.resolve("preferences").toFile(),root.resolve("layout").toFile());
        field(Preferences.class,"instance").set(null,prefs);prefs.set(Setting.ENABLED,1);
        for(String name:new String[]{"platformSupported","connected","altScreenVideo"})field(ScreenModule.class,name).set(null,true);
        field(CarplayBus.class,"running").set(CarplayBus.getInstance(),true);
        field(CarplayBus.class,"out").set(CarplayBus.getInstance(),new DataOutputStream(new ByteArrayOutputStream()));
        field(CarplayBus.class,"connectionGeneration").set(CarplayBus.getInstance(),8);
        int process=Integer.parseInt(java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
        Path owner=Paths.get("/tmp/MMI-Cockpit-Carplay.mirror.pid");
        check(!Files.exists(owner),"mirror owner already exists");
        Files.write(owner,Integer.toString(process).getBytes("US-ASCII"));
        try {
            VcPanel.start();VcPanel.presentation(true,false,false);
            long end=System.currentTimeMillis()+2000;
            while(((Integer)get("pid")!=process || !Files.exists(Paths.get(VcPanel.CONTROL)))
                    && System.currentTimeMillis()<end)Thread.sleep(10);
            check((Integer)get("pid")==process,"worker did not identify mirror");
            VcPanel.scale(1);hold();
            end=System.currentTimeMillis()+2000;
            while(!control()[6].equals("5") && System.currentTimeMillis()<end)Thread.sleep(10);
            String[] opened=control();
            check(opened[6].equals("5"),"worker did not publish page");
            check(!VcPanel.scale(-1),"input captured before renderer acknowledged");
            end=System.currentTimeMillis()+2000;
            while(!VcPanel.status().equals("ACTIVE") && System.currentTimeMillis()<end) {
                acknowledgeFile(control(),process);Thread.sleep(20);
            }
            check(VcPanel.status().equals("ACTIVE") && VcPanel.scale(-1),"matching acknowledgement did not grant routing");
            for(int attempt=0;attempt<2;attempt++) {
                Object epoch=get("epoch");
                synchronized(field(VcPanel.class,"LOCK").get(null)) {
                    set("ackUntil",System.currentTimeMillis()-1);
                    acknowledgeFile(control(),process);
                    if(attempt==0) {
                        Object focus=get("focus");
                        check(!VcPanel.scale(1),"expired cached acknowledgement granted input");
                        check((Boolean)get("open"),"callback closed before the worker read fresh presentation");
                        check(focus.equals(get("focus")),"unacknowledged input moved focus");
                    }
                }
                end=System.currentTimeMillis()+1000;
                while((Boolean)get("open") && !VcPanel.status().equals("ACTIVE") && System.currentTimeMillis()<end)
                    Thread.sleep(10);
                check(epoch.equals(get("epoch")) && VcPanel.status().equals("ACTIVE"),
                    "fresh renderer status was discarded before checking the cached lease");
            }
            Thread.sleep(450);
            check(!VcPanel.scale(-1) && !(Boolean)get("open"),"worker kept stale presentation");
            VcPanel.scale(1);hold();
            acknowledgeFile(opened,process);
            Thread.sleep(160);
            check(!VcPanel.status().equals("ACTIVE"),"old opening epoch was accepted");
            end=System.currentTimeMillis()+2000;
            while((Boolean)get("open") && System.currentTimeMillis()<end)Thread.sleep(20);
            check(!(Boolean)get("open"),"unacknowledged opening did not time out");
            VcPanel.scale(1);hold();
            Thread.sleep(150);acknowledgeFile(control(),process+1);Thread.sleep(150);
            check(!VcPanel.status().equals("ACTIVE"),"wrong mirror acquired routing");
            Files.write(Paths.get(VcPanel.STATUS),"broken\n".getBytes("US-ASCII"));Thread.sleep(150);
            check(!(Boolean)get("open") && VcPanel.status().contains("I/O"),"malformed status did not close/report");
        } finally {
            VcPanel.stop();
            long end=System.currentTimeMillis()+2000;
            while(get("worker")!=null && System.currentTimeMillis()<end)Thread.sleep(10);
            check(get("worker")==null,"worker did not stop");
            check(control()[6].equals("0"),"stop did not withdraw panel");
            for(Path p:new Path[]{owner,Paths.get(VcPanel.CONTROL),Paths.get(VcPanel.STATUS),
                    root.resolve("preferences"),root.resolve("layout")})Files.deleteIfExists(p);
            Files.delete(root);
        }
        System.out.println("VC panel real worker: atomic snapshots, matching/stale-epoch/PID acknowledgement gates, expiry, errors and stop PASS");
    }
}
