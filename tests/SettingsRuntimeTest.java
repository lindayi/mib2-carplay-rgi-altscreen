package com.luka.carplay.settings;

import java.io.*;
import java.nio.file.*;
import java.lang.reflect.Field;

/** Disposable Docker fixture only: never run against a head unit filesystem. */
public final class SettingsRuntimeTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void awaitValue(int id,int value) throws Exception {
        long end=System.currentTimeMillis()+4000;
        while(Preferences.get().snapshot().get(id)!=value && System.currentTimeMillis()<end)Thread.sleep(20);
        check(Preferences.get().snapshot().get(id)==value,"preference worker stalled");
    }
    static void awaitFile(Path p) throws Exception {
        long end=System.currentTimeMillis()+4000;
        while(!Files.exists(p) && System.currentTimeMillis()<end)Thread.sleep(20);
        check(Files.exists(p),"action did not start: "+SettingsRuntime.result());
    }
    public static void main(String[] args) throws Exception {
        check(new File("/.dockerenv").exists() && !new File("/proc/boot").exists(),"Docker-only test");
        Path prefs=Paths.get(Preferences.PATH);
        Path helper=Paths.get("/mnt/app/root/carplay-altscreen/bin/carplay_mmi_action.sh");
        check(!Files.exists(prefs) && !Files.exists(helper),"fixture paths already occupied");
        Files.createDirectories(prefs.getParent());Files.createDirectories(helper.getParent());
        String script="#!/bin/sh\nexec > \"$2\" 2>&1\ncase \"$1\" in\n"
            +"sync_mirror) exit 0;;\n"
            +"export_summary) touch /tmp/menu-short-started; sleep 2; echo done;;\n"
            +"export_full) touch /tmp/menu-long-started; exec sleep 60;;\n"
            +"*) exit 2;;\nesac\n";
        Files.write(helper,script.getBytes("UTF-8"));
        Preferences.get().set(Setting.ENABLED,0);
        SettingsRuntime.start();
        long start=System.currentTimeMillis();
        SettingsRuntime.set(Setting.TEXT_SIZE,1);
        check(System.currentTimeMillis()-start<500,"MMI callback blocked on persistence");
        awaitValue(Setting.TEXT_SIZE,1);
        SettingsRuntime.action("export_summary");
        awaitFile(Paths.get("/tmp/menu-short-started"));
        SettingsRuntime.set(Setting.TEXT_SIZE,0);
        awaitValue(Setting.TEXT_SIZE,0);
        long wait=System.currentTimeMillis()+5000;
        while(SettingsRuntime.busy() && System.currentTimeMillis()<wait)Thread.sleep(20);
        check(!SettingsRuntime.busy(),"short action remained busy");
        Field lock=SettingsRuntime.class.getDeclaredField("LOCK");lock.setAccessible(true);
        synchronized(lock.get(null)) {
            SettingsRuntime.action("reapply_layout");
            check(SettingsRuntime.busy(),"accepted but queued action reported idle");
        }
        wait=System.currentTimeMillis()+4000;
        while(SettingsRuntime.busy() && System.currentTimeMillis()<wait)Thread.sleep(20);
        check(SettingsRuntime.result().contains("Action failed:") && !SettingsRuntime.result().contains("helper"),
            "manual layout uses local worker and reports inactive-video failure: "+SettingsRuntime.result());
        SettingsRuntime.action("export_full");
        awaitFile(Paths.get("/tmp/menu-long-started"));
        SettingsRuntime.set(Setting.ZOOM,0);
        awaitValue(Setting.ZOOM,0);
        wait=System.currentTimeMillis()+50000;
        while(SettingsRuntime.busy() && System.currentTimeMillis()<wait)Thread.sleep(50);
        check(!SettingsRuntime.busy() && SettingsRuntime.result().contains("timed out"),"action timeout not surfaced");
        SettingsRuntime.stop();
        Thread.sleep(250);
        Field worker=SettingsRuntime.class.getDeclaredField("settingsThread");worker.setAccessible(true);
        check(worker.get(null)==null,"settings worker retained after stop");
        check(!com.luka.carplay.core.CarPlayApp.isActive(),"stop reactivated cockpit features");
        Files.delete(helper);Files.delete(prefs);
        Files.deleteIfExists(Paths.get("/tmp/menu-short-started"));Files.deleteIfExists(Paths.get("/tmp/menu-long-started"));
        System.out.println("SettingsRuntimeTest: nonblocking UI, preference progress during actions, bounded timeout, explicit error and shutdown PASS");
    }
}
