package com.luka.carplay.settings;

import com.luka.carplay.rgd.MapCards;
import java.io.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;

public final class MapCardControlTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args) throws Exception {
        check(new File("/.dockerenv").isFile() && !new File("/proc/boot").exists(),"Docker only");
        Path owner=Paths.get("/tmp/MMI-Cockpit-Carplay.mirror.pid");
        Path control=Paths.get(MapCardControl.CONTROL);
        check(!Files.exists(owner) && !Files.exists(control),"isolated fixture");
        MapCards.Snapshot snapshot=new MapCards.Snapshot();
        snapshot.media=snapshot.trip=true;snapshot.connection=7;snapshot.progress=250;snapshot.trackRevision=33;
        snapshot.title="Test song";snapshot.artist="Test artist";snapshot.playback="Paused";
        snapshot.tripText=new String[]{"12:34","25 min","10 mi"};
        String encoded=MapCardControl.encode(snapshot,123,4000);
        check(encoded.startsWith("CARDS3 123 4000 7 1 1 250 0 33\n"),"canonical field order");
        check(encoded.split("\n",-1).length==9 && encoded.endsWith("SPEED 0 -1 -1 -1 0 0 0\n"),"six text lines, speed state and terminal newline");
        Files.write(Paths.get(args[0]),encoded.getBytes("US-ASCII"));
        com.luka.carplay.core.SpeedBadge.Snapshot badge=new com.luka.carplay.core.SpeedBadge.Snapshot();
        badge.enabled=badge.wide=true;badge.speed=52;badge.limit=50;badge.unit=0;badge.source=2;
        badge.expires=2000;badge.connection=7;
        String withSpeed=MapCardControl.encode(snapshot,badge,123,4000);
        check(withSpeed.endsWith("SPEED 1 52 50 0 2 1 2000\n"),"camera badge field order");
        Files.write(Paths.get(args[0]).resolveSibling("speed-badge-control.txt"),withSpeed.getBytes("US-ASCII"));
        snapshot.title=new String(new char[129]).replace('\0','a');
        try {MapCardControl.encode(snapshot,123,4000);throw new AssertionError("oversized field accepted");}
        catch(IOException expected){}
        try {
            Files.createDirectories(control.getParent());
            check(MapCardControl.publish(true,true).equals("NOT_RUNNING"),"missing mirror cannot report publication");
            String pid=ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            Files.write(owner,(pid+"\n").getBytes("US-ASCII"));
            check(MapCardControl.publish(true,true).equals("WAITING_DATA"),"connected process is not media data");
            check(Preferences.read(control.toFile(),2048).startsWith("CARDS3 "+pid+" "),"PID-bound RAM snapshot");
            check(MapCardControl.publish(false,false).equals("OFF"),"off publication");
            Files.delete(control);Files.createDirectory(control);
            try {MapCardControl.publish(true,true);throw new AssertionError("rename failure hidden");}
            catch(IOException expected){}
            Files.delete(control);
            Files.write(owner,"invalid\n".getBytes("US-ASCII"));
            try {MapCardControl.publish(true,true);throw new AssertionError("invalid mirror hidden");}
            catch(IOException expected){}
        } finally {
            Files.deleteIfExists(control);Files.deleteIfExists(Paths.get(control+".new"));Files.deleteIfExists(owner);
        }
        System.out.println("MapCardControlTest: protocol, PID gate, RAM atomic publish, explicit failures PASS");
    }
}
