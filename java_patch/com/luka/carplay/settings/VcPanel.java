package com.luka.carplay.settings;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.framework.Log;
import java.io.*;
import java.util.StringTokenizer;

/** Wheel callbacks publish intent; the worker alone exchanges RAM-file snapshots. */
public final class VcPanel {
    static final String CONTROL="/ramdisk/carplay_vc_panel.control";
    static final String STATUS="/ramdisk/carplay_vc_panel.status";
    private static final Object LOCK=new Object();
    private static boolean running,open,pressed,held,largeMap,mapConfirmed;
    private static int run,connection=-1,confirmedConnection=-1,pressConnection=-1,pid,revision,shownRevision;
    private static long epoch=System.currentTimeMillis(),ackUntil,openedAt,lastInput;
    private static Thread worker;
    private static String page="root",problem="",lastHint="";
    private static final String[] parents=new String[4];
    private static final int[] focuses=new int[4];
    private static int depth,focus;
    private static long preferencesRevision=-1;

    private static final class Page {
        String title;
        int[] settings;
        String[] links;
        Page(String title,int[] settings,String[] links){this.title=title;this.settings=settings;this.links=links;}
        int count(){return settings==null?links.length:settings.length;}
    }
    private VcPanel(){}
    public static void start() {
        synchronized(LOCK) {
            if(running)return;
            if(worker!=null && worker.isAlive()){error("Previous VC panel worker is still stopping");return;}
            running=true;mapConfirmed=false;pressed=held=false;
            final int generation=++run;
            worker=new Thread(new Runnable(){public void run(){loop(generation);}},"carplay-vc-panel");
            worker.setDaemon(true);
            try {worker.start();}
            catch(RuntimeException e){running=false;error("Cannot start VC panel worker: "+e);}
        }
    }
    public static void stop() {
        synchronized(LOCK){running=false;run++;closeLocked();mapConfirmed=false;pressed=held=false;LOCK.notifyAll();}
    }
    private static boolean eligible() {
        Preferences.Snapshot s=Preferences.get().snapshot();
        return running && largeMap && ScreenModule.isConnected() && ScreenModule.isAltScreenVideo()
            && !com.luka.carplay.pdc.PdcSmallStageGuard.isParkingControlsActive()
            && s.on(Setting.ENABLED) && s.get(Setting.MODE)!=2
            && CarplayBus.getInstance().connectionGeneration()>=0;
    }
    private static boolean owns(long now) {
        if(!open)return false;
        if(!eligible() || connection!=CarplayBus.getInstance().connectionGeneration()
                || now<openedAt || now-lastInput>30000L || (ackUntil!=0 && now>=ackUntil)) {
            if(ackUntil!=0 && now>=ackUntil)error("VC panel presentation lease expired");
            closeLocked();return false;
        }
        return ackUntil>now;
    }
    private static void closeLocked() {
        if(open)Log.i("VcPanel","closed epoch="+epoch);
        open=false;ackUntil=0;shownRevision=0;epoch++;revision++;depth=0;focus=0;page="root";
    }
    public static void dismiss() {
        synchronized(LOCK){closeLocked();mapConfirmed=false;held=true;LOCK.notifyAll();}
    }
    public static void presentation(boolean large,boolean left,boolean right) {
        synchronized(LOCK) {
            if(largeMap!=large || left || right){closeLocked();mapConfirmed=false;held=true;}
            largeMap=large;
        }
    }
    /** True consumes only this project's scale actions; caller still replies with stock scale status. */
    public static boolean scale(int steps) {
        synchronized(LOCK) {
            long now=System.currentTimeMillis();
            if(owns(now)) {
                if(steps==0)return true;
                Page model=model();
                long next=(long)focus-steps;
                focus=(int)Math.max(0L,Math.min(model.count()-1L,next));
                revision++;lastInput=now;LOCK.notifyAll();return true;
            }
            if(!open && steps!=0 && eligible()) {
                mapConfirmed=true;confirmedConnection=CarplayBus.getInstance().connectionGeneration();
            }
            return false;
        }
    }
    /** Return true only for a completed ordinary short gesture outside our panel. */
    public static boolean roller(int state) {
        synchronized(LOCK) {
            long now=System.currentTimeMillis();
            if(state==1){
                if(!pressed){pressed=true;held=false;pressConnection=CarplayBus.getInstance().connectionGeneration();}
                return false;
            }
            if(pressConnection!=CarplayBus.getInstance().connectionGeneration()){pressed=false;return false;}
            if(state==3 || state==4 || state==5) {
                if(!pressed || held)return false;
                held=true;
                if(open){closeLocked();return false;}
                if(!eligible() || !mapConfirmed
                        || confirmedConnection!=CarplayBus.getInstance().connectionGeneration()) {
                    error("VC panel needs large-map View and a map-zoom detent after session/tab/drawer changes");
                    return false;
                }
                open=true;epoch++;revision=1;shownRevision=0;ackUntil=0;
                openedAt=lastInput=now;connection=confirmedConnection;depth=focus=0;page="root";
                preferencesRevision=Preferences.get().snapshot().revision;LOCK.notifyAll();
                Log.i("VcPanel","opening epoch="+epoch+" connection="+connection);
                return false;
            }
            if(state!=0 || !pressed)return false;
            pressed=false;
            if(held)return false;
            if(open) {
                if(owns(now) && shownRevision==revision)activate();
                return false;
            }
            return true;
        }
    }
    public static boolean back() {
        synchronized(LOCK) {
            boolean consumed=owns(System.currentTimeMillis());
            if(!open)return false;
            if(depth==0){closeLocked();return consumed;}
            page=parents[--depth];focus=focuses[depth];revision++;lastInput=System.currentTimeMillis();
            LOCK.notifyAll();
            return consumed;
        }
    }
    private static Page model() {
        if(page.equals("root"))return new Page("Carplay Altscreen",null,
            new String[]{"Enabled","Display mode","Map layout","Map mascot","More settings"});
        if(page.equals("more"))return new Page("More settings",null,
            new String[]{"Guidance","Appearance","Information bar","Controls","Reapply map layout"});
        if(page.equals("guidance"))return new Page("Guidance",new int[]{Setting.DISTANCE,Setting.ROAD,Setting.LANES,Setting.PROGRESS},null);
        if(page.equals("appearance"))return new Page("Appearance",new int[]{Setting.PRESET,Setting.TEXT_SIZE,Setting.ROAD_SCROLL,Setting.BACKGROUND},null);
        if(page.equals("information"))return new Page("Information bar",new int[]{Setting.INFO_DEFAULT,Setting.INFO_ROAD,Setting.INFO_RETURN},null);
        if(page.equals("controls"))return new Page("Controls",new int[]{Setting.ZOOM,Setting.ZOOM_SPEED,Setting.TOUCHPAD,Setting.TOUCH_SENSITIVITY},null);
        int id=Integer.parseInt(page.substring(7));
        return new Page(Setting.ALL[id].label,null,Setting.ALL[id].choices);
    }
    private static void enter(String next) {
        if(depth>=parents.length){error("VC panel navigation depth exceeded");closeLocked();return;}
        parents[depth]=page;focuses[depth++]=focus;page=next;focus=0;
        if(next.startsWith("choice:"))focus=Preferences.get().snapshot().get(Integer.parseInt(next.substring(7)));
        revision++;
    }
    private static void setting(int id) {
        if(Setting.ALL[id].isSwitch())SettingsRuntime.set(id,1-Preferences.get().snapshot().get(id));
        else enter("choice:"+id);
    }
    private static void activate() {
        lastInput=System.currentTimeMillis();
        if(SettingsRuntime.busy())return;
        Log.i("VcPanel","select page="+page+" row="+focus+" epoch="+epoch);
        if(page.startsWith("choice:")) {
            SettingsRuntime.set(Integer.parseInt(page.substring(7)),focus);
            back();
        } else if(page.equals("root")) {
            if(focus==0){SettingsRuntime.set(Setting.ENABLED,0);closeLocked();}
            else if(focus==4)enter("more");
            else setting(new int[]{Setting.ENABLED,Setting.MODE,Setting.LAYOUT,Setting.MASCOT}[focus]);
        } else if(page.equals("more")) {
            if(focus==4)SettingsRuntime.action("reapply_layout");
            else enter(new String[]{"guidance","appearance","information","controls"}[focus]);
        } else setting(model().settings[focus]);
        revision++;LOCK.notifyAll();
    }
    private static String text(String value,int maximum) {
        if(value==null)return "";
        StringBuffer result=new StringBuffer();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);result.append(c>=32 && c<=126?c:' ');
        }
        String s=result.toString();
        return s.length()<=maximum?s:s.substring(0,maximum-3)+"...";
    }
    private static String snapshot(long now) {
        StringBuffer out=new StringBuffer("VCPANEL1 "+pid+" "+epoch+" "+revision+" "+(now+600L)+" "+Math.max(0,connection)+" ");
        if(!open)return out.append("0 0\n").toString();
        Page m=model();Preferences.Snapshot prefs=Preferences.get().snapshot();
        out.append(m.count()).append(' ').append(focus).append('\n').append(text(m.title,39)).append('\n');
        String hint=SettingsRuntime.notice();
        boolean reconnect=SettingsRuntime.reconnectPending();
        if(reconnect && hint.startsWith("Saved:"))hint="Saved; CarPlay reconnect pending";
        if(hint.length()==0) {
            boolean choiceNeedsReconnect=page.startsWith("choice:") &&
                Setting.ALL[Integer.parseInt(page.substring(7))].reconnect;
            hint=reconnect?"Reconnect CarPlay to apply saved changes":
                choiceNeedsReconnect?"Layout changes require a CarPlay reconnect":"Hold roller: close";
        }
        out.append(text(hint,63)).append('\n');
        for(int i=0;i<m.count();i++) {
            int id=m.settings==null?-1:m.settings[i],kind=2,checked=0;
            String label=m.links==null?Setting.ALL[id].label:m.links[i],value="";
            if(page.startsWith("choice:")) {
                kind=3;checked=prefs.get(Integer.parseInt(page.substring(7)))==i?1:0;
            } else {
                if(page.equals("root") && i<4)id=new int[]{Setting.ENABLED,Setting.MODE,Setting.LAYOUT,Setting.MASCOT}[i];
                if(id>=0) {
                    if(Setting.ALL[id].isSwitch()){kind=1;checked=prefs.get(id);}
                    else value=Setting.ALL[id].choices[prefs.get(id)];
                }
            }
            out.append(kind).append(' ').append(checked).append('\t').append(text(label,39)).append('\t')
                .append(text(value,31)).append('\n');
        }
        return out.toString();
    }
    static long[] parseStatus(String value) throws IOException {
        StringTokenizer tokens=new StringTokenizer(value);
        if(tokens.countTokens()!=7 || !tokens.nextToken().equals("VCPANEL1"))throw new IOException("Invalid VC panel status");
        long[] fields=new long[6];
        try {for(int i=0;i<fields.length;i++)fields[i]=Long.parseLong(tokens.nextToken());}
        catch(NumberFormatException e){throw new IOException("Invalid VC panel status number");}
        return fields;
    }
    private static int owner() throws IOException {
        File file=new File("/tmp/MMI-Cockpit-Carplay.mirror.pid");
        if(!file.isFile())return 0;
        try {
            int value=Integer.parseInt(Preferences.read(file,32).trim());
            return value>1 && new File("/proc/"+value).isDirectory()?value:0;
        } catch(NumberFormatException e){throw new IOException("Invalid VC panel mirror PID");}
    }
    private static void publish(String value) throws IOException {
        File target=new File(CONTROL),temporary=new File(CONTROL+".new");
        FileOutputStream out=new FileOutputStream(temporary);
        try {out.write(value.getBytes("US-ASCII"));out.flush();}finally{out.close();}
        if(!temporary.renameTo(target))throw new IOException("Cannot publish VC panel control");
    }
    private static void error(String value) {
        if(!value.equals(problem)){problem=value;Log.w("VcPanel",value);}
    }
    public static String status() {
        synchronized(LOCK){return open?(ackUntil>System.currentTimeMillis()?"ACTIVE":"OPENING"):problem.length()==0?"CLOSED":problem;}
    }
    private static void loop(int generation) {
        try {
            for(;;) {
                synchronized(LOCK){if(!running || run!=generation)break;}
                try {
                    int process=owner();
                    String data;long ticket;int version;
                    synchronized(LOCK) {
                        if(!running || run!=generation)break;
                        long now=System.currentTimeMillis();
                        if(pid!=process){closeLocked();mapConfirmed=false;pid=process;}
                        owns(now);
                        if(!eligible())mapConfirmed=false;
                        if(open && (pid==0 || now-openedAt>1500L && ackUntil==0)) {
                            error("VC panel presentation acknowledgement unavailable");closeLocked();
                        }
                        long prefs=Preferences.get().snapshot().revision;
                        if(open && preferencesRevision!=prefs){preferencesRevision=prefs;revision++;}
                        String hint=SettingsRuntime.notice()+SettingsRuntime.reconnectPending();
                        if(open && !hint.equals(lastHint)){lastHint=hint;revision++;}
                        data=snapshot(now);ticket=epoch;version=revision;
                    }
                    publish(data);
                    File status=new File(STATUS);
                    long[] ack=status.isFile()?parseStatus(Preferences.read(status,256)):null;
                    synchronized(LOCK) {
                        long now=System.currentTimeMillis();
                        if(running && run==generation && open && epoch==ticket && ack!=null
                                && ack[0]==pid && ack[1]==ticket && ack[2]>0 && ack[2]<=version) {
                            if(ack[5]<0){error("VC panel renderer rejected the frame");closeLocked();}
                            else if(ack[5]==0 && ackUntil!=0){error("VC panel renderer withdrew presentation");closeLocked();}
                            else if(ack[5]==1 && ack[3]>now && ack[3]-now<=300L && ack[4]==connection) {
                                if(ackUntil==0)Log.i("VcPanel","presented epoch="+epoch+" pid="+pid);
                                ackUntil=ack[3];shownRevision=(int)ack[2];problem="";
                            }
                        }
                    }
                } catch(IOException e) {
                    synchronized(LOCK){error("VC panel I/O: "+e.getMessage());closeLocked();}
                } catch(SecurityException e) {
                    synchronized(LOCK){error("VC panel access denied");closeLocked();}
                }
                synchronized(LOCK) {
                    if(!running || run!=generation)break;
                    try {LOCK.wait(100L);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                }
            }
        } catch(RuntimeException e) {
            synchronized(LOCK){error("VC panel worker failed: "+e);closeLocked();running=false;}
        } finally {
            synchronized(LOCK){if(run==generation){running=false;closeLocked();}}
            try {publish("VCPANEL1 0 0 0 0 0 0 0\n");}
            catch(IOException e){Log.w("VcPanel","Cannot withdraw panel: "+e);}
            catch(SecurityException e){Log.w("VcPanel","Cannot withdraw panel: "+e);}
            synchronized(LOCK){if(worker==Thread.currentThread())worker=null;}
        }
    }
}
