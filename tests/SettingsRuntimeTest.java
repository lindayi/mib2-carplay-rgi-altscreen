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
            +"export_full) touch /tmp/menu-long-started; echo EXPORT_STAGE=fixture-wait; exec sleep 60;;\n"
            +"*) exit 2;;\nesac\n";
        Files.write(helper,script.getBytes("UTF-8"));
        Preferences.get().set(Setting.ENABLED,0);
        SettingsRuntime.start();
        long start=System.currentTimeMillis();
        SettingsRuntime.set(Setting.TEXT_SIZE,1);
        check(System.currentTimeMillis()-start<500,"MMI callback blocked on persistence");
        awaitValue(Setting.TEXT_SIZE,1);
        long saveWait=System.currentTimeMillis()+4000;
        while(!SettingsRuntime.result().startsWith("Saved:") && System.currentTimeMillis()<saveWait)Thread.sleep(10);
        String saved=SettingsRuntime.result();
        check(saved.contains("Overlay text size") && saved.contains("Large"),"save notice lacks changed setting");
        Field savedAt=SettingsRuntime.class.getDeclaredField("savedAt");savedAt.setAccessible(true);
        long savedTime=savedAt.getLong(null);
        check(SettingsRuntime.notice(savedTime+4999).equals(saved),"save notice expired too early");
        check(SettingsRuntime.notice(savedTime+5000).equals(""),"old save remains in footer");
        check(SettingsRuntime.notice(savedTime-1).equals(""),"clock rollback resurrects notice");
        check(SettingsRuntime.result().equals(saved),"expiry erased Last result");
        java.util.concurrent.CountDownLatch expired=new java.util.concurrent.CountDownLatch(1);
        SettingsRuntime.Listener listener=()->{if(SettingsRuntime.notice().equals(""))expired.countDown();};
        SettingsRuntime.addListener(listener);
        check(expired.await(7,java.util.concurrent.TimeUnit.SECONDS),"worker did not publish notice expiry");
        SettingsRuntime.removeListener(listener);
        SettingsRuntime.set(Setting.TEXT_SIZE,1);
        saveWait=System.currentTimeMillis()+4000;
        while((!SettingsRuntime.result().startsWith("Saved:") || SettingsRuntime.notice().equals(""))
                && System.currentTimeMillis()<saveWait)Thread.sleep(10);
        check(SettingsRuntime.notice().equals(saved),"same setting cannot show a fresh save");
        awaitFile(Paths.get(MascotControl.CONTROL));
        check(Preferences.read(new File(MascotControl.CONTROL),96).startsWith("MASCOT2 0 0 "),
            "inactive session must not enable the mascot");
        SettingsRuntime.action("export_summary");
        awaitFile(Paths.get("/tmp/menu-short-started"));
        int mascot=Math.min(2,Setting.ALL[Setting.MASCOT].choices.length-1);
        SettingsRuntime.set(Setting.MASCOT,mascot);
        awaitValue(Setting.MASCOT,mascot);
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
        check(SettingsRuntime.notice(System.currentTimeMillis()+60000).equals(SettingsRuntime.result()),
            "failure expired like a successful save");
        SettingsRuntime.action("export_full");
        awaitFile(Paths.get("/tmp/menu-long-started"));
        SettingsRuntime.set(Setting.ZOOM,0);
        awaitValue(Setting.ZOOM,0);
        wait=System.currentTimeMillis()+50000;
        while(SettingsRuntime.busy() && System.currentTimeMillis()<wait)Thread.sleep(50);
        check(!SettingsRuntime.busy() && SettingsRuntime.result().contains("timed out"),"action timeout not surfaced");
        String failure=Preferences.read(new File("/tmp/carplay_menu_action.failure.log"),16384);
        check(failure.contains("action=export_full") && failure.contains("timed out")
            && failure.contains("EXPORT_STAGE=fixture-wait"),"timeout output was discarded");
        Files.write(helper,("#!/bin/sh\nexec > \"$2\" 2>&1\necho EXPORT_ERROR=fixture_sd_readonly\nexit 7\n").getBytes("UTF-8"));
        SettingsRuntime.action("export_summary");
        wait=System.currentTimeMillis()+4000;
        while(SettingsRuntime.busy() && System.currentTimeMillis()<wait)Thread.sleep(20);
        failure=Preferences.read(new File("/tmp/carplay_menu_action.failure.log"),16384);
        check(failure.contains("action=export_summary") && failure.contains("returned 7")
            && failure.contains("EXPORT_ERROR=fixture_sd_readonly"),"nonzero output was discarded");
        Path huge=Paths.get("/tmp/menu-huge-output");
        byte[] oversized=new byte[20000];java.util.Arrays.fill(oversized,(byte)'x');
        Files.write(huge,oversized);
        check(SettingsRuntime.actionTail(huge.toFile()).length()<8300,"action output tail is unbounded");
        Files.delete(huge);
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
