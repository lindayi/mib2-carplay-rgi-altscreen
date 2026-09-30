package com.luka.carplay.settings;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;

public final class MascotControlTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void write(Path path,String text) throws Exception {Files.write(path,text.getBytes("US-ASCII"));}
    static void invalidStatus(Path path,String text) throws Exception {
        write(path,text);
        try {MascotControl.status();throw new AssertionError("invalid status accepted");}
        catch(IOException expected){}
    }
    public static void main(String[] args) throws Exception {
        check(new File("/.dockerenv").exists() && !new File("/proc/boot").exists(),"Docker only");
        Path owner=Paths.get("/tmp/MMI-Cockpit-Carplay.mirror.pid");
        Path control=Paths.get(MascotControl.CONTROL),status=Paths.get(MascotControl.STATUS);
        Files.createDirectories(control.getParent());
        check(!control.toString().startsWith("/tmp/") && !status.toString().startsWith("/tmp/"),
            "atomic snapshots cannot use QNX shared memory");
        check(!Files.exists(owner) && !Files.exists(status),"fixture paths occupied");
        String pid=ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        int count=Setting.ALL[Setting.MASCOT].choices.length;
        try {
            MascotControl.publish(count>1?1:0);
            check(Preferences.read(control.toFile(),96).startsWith("MASCOT2 0 0 "),"missing owner gates On");
            write(owner,pid+"\n");
            for(int selected=1;selected<count;selected++) {
                long start=System.currentTimeMillis();MascotControl.publish(selected);
                String packet=Preferences.read(control.toFile(),96);
                check(packet.startsWith("MASCOT2 "+selected+" "+pid+" ") && packet.endsWith("\n"),"canonical PID-bound packet");
                long expires=Long.parseLong(packet.trim().split(" ")[3]);
                check(expires>=start+4000 && expires<=System.currentTimeMillis()+4000,"four-second lease");
            }
            MascotControl.publish(0);
            check(Preferences.read(control.toFile(),96).startsWith("MASCOT2 0 0 "),"Off packet");
            String record="pid="+pid+"\nstate=RACCOON\nexpires="+(System.currentTimeMillis()+4000)+"\n";
            write(status,record);check(MascotControl.status().equals("RACCOON"),"live status");
            write(status,record.replace("RACCOON","ACTIVE"));
            check(MascotControl.status().equals("ACTIVE"),"generic active status");
            write(status,record.replace("RACCOON","CAPYBARA"));
            check(MascotControl.status().equals("CAPYBARA"),"live Capybara status");
            write(status,record.replace("RACCOON","LIZARD"));
            check(MascotControl.status().equals("LIZARD"),"live Lizard status");
            for(int selected:new int[]{-1,count}) {
                try {MascotControl.publish(selected);throw new AssertionError("invalid selection accepted");}
                catch(IOException expected){}
            }
            write(status,record.replace("pid="+pid,"pid=2147483647"));
            check(MascotControl.status().equals("NOT_RUNNING"),"stale PID rejected");
            write(status,"pid="+pid+"\nstate=NIAN\nexpires=1\n");
            check(MascotControl.status().equals("NOT_RUNNING"),"expired status rejected");
            invalidStatus(status,record+"state=NIAN\n");
            invalidStatus(status,record.replace("RACCOON","<script>"));
            invalidStatus(status,"pid="+pid+"\nstate=RACCOON\n");
            write(owner,"bad\n");
            if(count>1)try {MascotControl.publish(1);throw new AssertionError("bad owner accepted");}catch(IOException expected){}
            Files.delete(control);Files.createDirectory(control);
            try {MascotControl.publish(0);throw new AssertionError("publish failure hidden");}catch(IOException expected){}
        } finally {
            Files.deleteIfExists(control);Files.deleteIfExists(Paths.get(control+".new"));
            Files.deleteIfExists(owner);Files.deleteIfExists(status);
        }
        System.out.println("MascotControlTest: worker packet, PID/expiry status, validation and I/O failures PASS");
    }
}
