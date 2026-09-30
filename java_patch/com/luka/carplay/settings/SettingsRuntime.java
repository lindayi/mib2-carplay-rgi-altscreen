package com.luka.carplay.settings;

import com.luka.carplay.core.CarPlayApp;
import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.cluster.ClusterLayerController;
import com.luka.carplay.input.TouchpadController;
import com.luka.carplay.rgd.RendererServer;
import com.luka.carplay.framework.Log;
import java.io.*;
import java.util.ArrayList;

/** One bounded command worker; native MMI callbacks never wait on files/processes. */
public final class SettingsRuntime {
    private static final String SESSION="/ramdisk/carplay_menu_session";
    private static final String HELPER="/mnt/app/root/carplay-altscreen/bin/carplay_mmi_action.sh";
    private static final Object LOCK=new Object();
    private static final ArrayList jobs=new ArrayList();
    private static final ArrayList listeners=new ArrayList();
    private static volatile boolean started;
    private static int generation;
    private static Thread settingsThread;
    private static volatile Process actionProcess;
    private static volatile String result="Ready";
    private static volatile long savedAt;
    private static String lastNotice="";
    private static String lastPanelStatus="";
    private static volatile boolean busy;
    private static boolean actionBusy,syncPending;
    private static String pendingAction;
    private static int actionSequence;
    private static volatile boolean sessionEnabled;
    private static volatile boolean sessionKnown;
    private static volatile boolean sessionVideo;
    private static volatile boolean sessionVerbose;
    private static volatile String sessionError="";
    private static volatile boolean layoutPending;
    private static volatile boolean readyMarker;
    private static String lastSession="";
    private static int lastLayout=-1;
    private static volatile String mirrorHealth="UNKNOWN",clusterContext="UNKNOWN";
    private static volatile String mascotHealth="OFF";
    private static volatile String cardHealth="OFF";
    private static int lastEffective=-1;
    private static long lastRevision=-1;
    public interface Listener { void changed(); }
    private static final class Job {
        int id,value; String action;
        Job(int id,int value,String action){this.id=id;this.value=value;this.action=action;}
    }
    private SettingsRuntime(){}
    public static void start() {
        synchronized(LOCK) {
            if(started)return;
            if(settingsThread!=null && settingsThread.isAlive()) {
                result="Previous settings worker is still stopping";return;
            }
            CarPlayApp.setFeaturesEnabled(false);
            started=true;
            result="Loading preferences...";
            final int run=++generation;
            Thread worker=new Thread(new Runnable(){public void run(){loop(run);}},"carplay-settings");
            worker.setDaemon(true);
            settingsThread=worker;
            try {worker.start();}
            catch(RuntimeException e){started=false;result="Settings worker unavailable";Log.e("Settings",result,e);}
        }
    }
    private static boolean current(int run){synchronized(LOCK){return started && generation==run;}}
    public static void stop() {
        Thread thread;
        synchronized(LOCK) {
            started=false;generation++;jobs.clear();pendingAction=null;syncPending=false;
            thread=settingsThread;LOCK.notifyAll();
        }
        if(thread!=null)thread.interrupt();
        Process process=actionProcess;
        if(process!=null)process.destroy();
        CarPlayApp.setFeaturesEnabled(false);
        if(thread!=null && thread!=Thread.currentThread()) {
            try {thread.join(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        synchronized(LOCK){if(thread==settingsThread && (thread==null || !thread.isAlive()))settingsThread=null;}
    }
    public static void addListener(Listener l){synchronized(LOCK){if(!listeners.contains(l))listeners.add(l);}}
    public static void removeListener(Listener l){synchronized(LOCK){listeners.remove(l);}}
    public static String result(){return result;}
    public static String notice(){return notice(System.currentTimeMillis());}
    static String notice(long now) {
        synchronized(LOCK) {
            String message=result;
            if(message.equals("Ready"))return "";
            if(message.startsWith("Saved:") && (now<savedAt || now-savedAt>=5000L))return "";
            return message;
        }
    }
    public static boolean busy(){
        synchronized(LOCK){return busy || actionBusy || !jobs.isEmpty() || pendingAction!=null || syncPending;}
    }
    public static boolean reconnectPending(){
        Preferences.Snapshot s=Preferences.get().snapshot();
        return !sessionKnown || s.on(Setting.ENABLED)!=sessionEnabled
            || (s.on(Setting.ENABLED) && s.get(Setting.MODE)!=2)!=sessionVideo
            || s.on(Setting.VERBOSE)!=sessionVerbose || layoutPending;
    }
    private static void changed(){
        Object[] copy;
        synchronized(LOCK){copy=listeners.toArray();}
        for(int i=0;i<copy.length;i++)try{((Listener)copy[i]).changed();}
        catch(RuntimeException e){Log.w("Settings","UI notification failed: "+e);}
    }
    public static void set(int id,int value){enqueue(new Job(id,value,null));}
    public static void action(String action){
        if(!"reset".equals(action) && !"export_summary".equals(action)
                && !"export_full".equals(action) && !"restart_video".equals(action)
                && !"reapply_layout".equals(action))
            throw new IllegalArgumentException("Unknown menu action");
        enqueue(new Job(-1,0,action));
    }
    private static void enqueue(Job job){
        start();
        synchronized(LOCK) {
            if(!started)result="Settings service unavailable";
            else if(jobs.size()>=8)result="Busy - try again shortly";
            else {jobs.add(job);result="Saving / running...";LOCK.notifyAll();}
        }
        changed();
    }
    static boolean[] parseSession(String text) throws IOException {
        int pid=-1,enabled=-1,video=-1,verbose=-1,configError=-1;
        java.util.HashSet seen=new java.util.HashSet();
        BufferedReader reader=new BufferedReader(new StringReader(text));
        for(String line;(line=reader.readLine())!=null;) {
            int e=line.indexOf('=');
            if(e<=0 || e!=line.lastIndexOf('='))throw new IOException("Invalid session status");
            String key=line.substring(0,e),value=line.substring(e+1);
            if(!seen.add(key))throw new IOException("Duplicate session status field");
            try {
                if(key.equals("pid"))pid=Integer.parseInt(value);
                else if(key.equals("enabled"))enabled=Integer.parseInt(value);
                else if(key.equals("video"))video=Integer.parseInt(value);
                else if(key.equals("verbose"))verbose=Integer.parseInt(value);
                else if(key.equals("config_error"))configError=Integer.parseInt(value);
                else throw new IOException("Unknown session status field");
            } catch(NumberFormatException x){throw new IOException("Invalid session status");}
        }
        if(pid<=1 || enabled<0 || enabled>1 || video<0 || video>1 || verbose<0 || verbose>1
                || configError<0 || configError>1)throw new IOException("Incomplete session status");
        File owner=new File("/ramdisk/carplay_supervisor.owner");
        boolean known=new File("/proc/"+pid).isDirectory() && owner.isFile()
            && Preferences.read(owner,32).trim().equals(Integer.toString(pid));
        return new boolean[]{known,known && enabled==1 && configError==0,
            known && video==1 && configError==0,known && verbose==1};
    }
    private static void tick(int run) {
        Preferences prefs=Preferences.get();prefs.refresh();
        boolean[] session={false,false,false,false};
        String sessionText="";
        String problem="";
        try {
            File f=new File(SESSION);
            if(f.isFile()){sessionText=Preferences.read(f,1024);session=parseSession(sessionText);}
        } catch(IOException e){problem=e.getMessage();}
        if(!problem.equals(sessionError)) {
            sessionError=problem;
            if(problem.length()!=0)Log.w("Settings","session status unavailable: "+problem);
            changed();
        }
        boolean refreshLog=session[0] && (!sessionKnown || sessionVerbose!=session[3]);
        sessionKnown=session[0];sessionEnabled=session[1];sessionVideo=session[2];sessionVerbose=session[3];
        if(refreshLog)Log.refreshLevel();
        boolean ready=new File("/tmp/mmi-mirror-basevideo.ready").exists();
        if(ready!=readyMarker){readyMarker=ready;changed();}
        String health=readStatusField("/ramdisk/MMI-Cockpit-Carplay.mirror.health","HEALTH_STATE",mirrorHealth);
        String context=readStatusField("/tmp/carplay_cluster.ctx","ctx",clusterContext);
        if(!health.equals(mirrorHealth) || !context.equals(clusterContext)) {
            mirrorHealth=health;clusterContext=context;changed();
        }
        Preferences.Snapshot s=prefs.snapshot();
        if(!current(run))return;
        if(sessionKnown && !sessionText.equals(lastSession)) {
            lastSession=sessionText;layoutPending=false;
        } else if(lastLayout>=0 && lastLayout!=s.get(Setting.LAYOUT))layoutPending=true;
        lastLayout=s.get(Setting.LAYOUT);
        boolean effective=s.on(Setting.ENABLED) && sessionEnabled && prefs.error().length()==0;
        String mascotState;
        try {
            MascotControl.publish(effective && sessionVideo && s.get(Setting.MODE)!=2?s.get(Setting.MASCOT):0);
            try {mascotState=ready && sessionVideo?MascotControl.status():"NOT_RUNNING";}
            catch(IOException e) {
                mascotState="STATUS_ERROR";
                if(!mascotState.equals(mascotHealth))Log.e("Settings","Cannot read map mascot status",e);
            }
        } catch(IOException e) {
            mascotState="CONTROL_ERROR";
            if(!mascotState.equals(mascotHealth))Log.e("Settings","Map mascot unavailable",e);
        }
        if(!mascotState.equals(mascotHealth)){mascotHealth=mascotState;changed();}
        String cards;
        boolean cardsAllowed=effective && ready && sessionVideo && s.get(Setting.MODE)!=2;
        try {
            cards=MapCardControl.publish(cardsAllowed && s.on(Setting.NOW_PLAYING_CARD),cardsAllowed && s.on(Setting.TRIP_CARD));
        } catch(IOException e) {
            cards="CONTROL_ERROR";
            if(!cards.equals(cardHealth))Log.e("Settings","Map cards unavailable",e);
        }
        if(!cards.equals(cardHealth)){cardHealth=cards;changed();}
        CarPlayApp.setGuidanceEnabled(s.get(Setting.MODE)!=1);
        ScreenModule.setVideoAllowed(s.get(Setting.MODE)!=2 && sessionVideo);
        CarPlayApp.setFeaturesEnabled(effective);
        TouchpadController.getInstance().configure(effective && s.on(Setting.TOUCHPAD),s.get(Setting.TOUCH_SENSITIVITY));
        if(s.revision!=lastRevision) {
            lastRevision=s.revision;
            if(result.equals("Loading preferences..."))result=prefs.error().length()==0?"Ready":"Preferences invalid; reset to repair";
            ClusterLayerController.reapply();
            RendererServer.preferencesChanged();
            changed();
        }
        int desired=!s.on(Setting.ENABLED)?0:
            effective && sessionVideo && s.get(Setting.MODE)!=2 && CarPlayApp.isSessionConnected()?1:2;
        if(lastEffective!=desired) {
            lastEffective=desired;
            synchronized(LOCK){syncPending=true;}
            changed();
        }
        String notice=notice();
        if(!notice.equals(lastNotice)){lastNotice=notice;changed();}
        String panel=VcPanel.status();
        if(!panel.equals(lastPanelStatus)){lastPanelStatus=panel;changed();}
    }
    private static void loop(int run){
        while(current(run)) {
            Job job=null;
            synchronized(LOCK){if(!jobs.isEmpty()){job=(Job)jobs.remove(0);busy=true;}}
            if(job!=null) {
                try {
                    if(job.action==null) {
                        Preferences.get().set(job.id,job.value);
                        synchronized(LOCK) {
                            savedAt=System.currentTimeMillis();
                            result="Saved: "+Setting.ALL[job.id].label+" - "+Setting.ALL[job.id].choices[job.value];
                        }
                        if(job.id==Setting.LAYOUT)layoutPending=true;
                    } else if(job.action.equals("reapply_layout")) {
                        com.luka.carplay.cluster.AltScreenCluster.reapplyLayout();
                        result="Layout queued to receiver; not phone-confirmed";
                    } else if(job.action.equals("reset")) {
                        Preferences.get().reset();
                        layoutPending=true;
                        result="Defaults saved; master switch preserved. Reconnect for map layout.";
                    } else {
                        synchronized(LOCK) {
                            if(pendingAction!=null)result="An action is already queued";
                            else {pendingAction=job.action;result=actionBusy?"Action queued":"Running action...";}
                        }
                    }
                } catch(IOException e){result=(job.action==null?"Not saved: ":"Action failed: ")+e.getMessage();Log.e("Settings",result,e);}
                catch(SecurityException e){result="Not saved: access denied";Log.e("Settings",result,e);}
                finally {busy=false;changed();}
            }
            try {tick(run);}
            catch(RuntimeException e){CarPlayApp.setFeaturesEnabled(false);result="Settings update failed: "+e.getMessage();Log.e("Settings",result,e);changed();}
            if(current(run))startActionWorker();
            synchronized(LOCK) {
                if(jobs.isEmpty())try{LOCK.wait(1000);}catch(InterruptedException e){Log.w("Settings","worker interrupted");}
            }
        }
    }
    private static void startActionWorker() {
        final String action;
        final int run;
        synchronized(LOCK) {
            if(actionBusy || !syncPending && pendingAction==null)return;
            if(syncPending){action="sync_mirror";syncPending=false;}
            else {action=pendingAction;pendingAction=null;}
            actionBusy=true;
            run=generation;
        }
        Thread worker=new Thread(new Runnable(){public void run(){
            try {
                if(!current(run))return;
                String message=runHelper(action,run);
                if(current(run) && !action.equals("sync_mirror"))result=message;
            } catch(IOException e){
                if(current(run))result="Action failed: "+e.getMessage();
                Log.e("Settings","action="+action+" failed: "+e.getMessage(),e);
            }
            finally {synchronized(LOCK){actionBusy=false;LOCK.notifyAll();}changed();}
        }},"carplay-settings-action");
        worker.setDaemon(true);
        try {worker.start();}
        catch(RuntimeException e){synchronized(LOCK){actionBusy=false;}result="Cannot start action worker";Log.e("Settings",result,e);changed();}
    }
    private static String runHelper(String action,int run) throws IOException {
        Process process=null;
        File output;
        synchronized(LOCK){output=new File("/tmp/carplay_menu_action."+(++actionSequence)+".log");}
        try {
            if(!new File(HELPER).isFile())throw new IOException("Matching runtime helper is not installed");
            process=Runtime.getRuntime().exec(new String[]{"/bin/sh",HELPER,action,output.getPath()});
            if(!current(run))throw new IOException("Settings service stopped");
            actionProcess=process;
            process.getOutputStream().close();
            int polls=0;
            int rc;
            for(;;) {
                if(!current(run))throw new IOException("Settings service stopped");
                try {
                    rc=process.exitValue();break;
                } catch(IllegalThreadStateException running) {
                    if(++polls>=450)throw new IOException("Action timed out; partial diagnostics may remain on SD");
                    Thread.sleep(100);
                }
            }
            String text=actionTail(output);
            Log.i("Settings","action="+action+" rc="+rc+" "+text);
            if(rc!=0)throw new IOException("Runtime helper returned "+rc+"; see diagnostics");
            return action.startsWith("export_")?"Diagnostics saved to SD: MMI-Cockpit-Carplay/logs/exports":"Cockpit video request completed";
        } catch(InterruptedException e){
            Thread.currentThread().interrupt();
            retainActionFailure(action,output,"Action interrupted");
            throw new IOException("Action interrupted");
        } catch(IOException e) {
            retainActionFailure(action,output,e.getMessage());
            throw e;
        }
        finally {
            if(actionProcess==process)actionProcess=null;
            if(process!=null){process.destroy();try{process.getInputStream().close();process.getErrorStream().close();}catch(IOException e){Log.w("Settings","process stream close failed");}}
            if(output.exists() && !output.delete())Log.w("Settings","could not remove action output");
        }
    }
    static String actionTail(File output) throws IOException {
        if(!output.isFile())return "No action output";
        RandomAccessFile input=new RandomAccessFile(output,"r");
        try {
            long length=input.length();
            int size=(int)Math.min(length,8192L);
            input.seek(length-size);
            byte[] bytes=new byte[size];input.readFully(bytes);
            return (length>size?"[output tail]\n":"")+new String(bytes,"UTF-8");
        } finally {input.close();}
    }
    private static void retainActionFailure(String action,File output,String reason) {
        String detail="action="+action+" failure="+reason+"\n";
        try {detail+=actionTail(output);}
        catch(IOException e){detail+="Cannot read action output: "+e.getMessage();}
        Log.e("Settings",detail);
        try {
            FileOutputStream saved=new FileOutputStream("/tmp/carplay_menu_action.failure.log");
            try {saved.write(detail.getBytes("UTF-8"));saved.flush();}finally{saved.close();}
        } catch(IOException e){Log.e("Settings","Cannot retain action failure",e);}
    }
    public static String[] status() {
        String error=Preferences.get().error();
        return new String[]{
            "Build: "+CarPlayApp.BUILD_ID,
            "Requested: "+(Preferences.get().snapshot().on(Setting.ENABLED)?"On":"Off"),
            "Phone session: "+(CarPlayApp.isSessionConnected()?"Connected":"Disconnected"),
            "Connection: "+(!sessionKnown?"No current session":sessionEnabled?"AltScreen enabled":"Stock receiver bypass"),
            "Second stream: "+(sessionVideo?"Enabled for this session":"Not requested"),
            sessionError.length()==0?"Session record: valid or not connected":"Session error: "+sessionError,
            "Apply state: "+(reconnectPending()?"Reconnect CarPlay":"Session setting matches"),
            "Cockpit integration: "+(CarPlayApp.isActive()?"Active":"Inactive"),
            "Mirror health: "+mirrorHealth,
            "Map mascot renderer: "+mascotHealth,
            "Map cards control (not presentation): "+cardHealth,
            "VC quick settings: "+VcPanel.status(),
            "Last cluster context: "+clusterContext,
            "Video marker: "+(readyMarker?"Present (not pixel proof)":"Absent"),
            error.length()==0?"Preferences: valid":"Preferences error: "+error,
            "Last action: "+result
        };
    }
    private static String readStatusField(String path,String key,String previous) {
        File file=new File(path);
        if(!file.isFile())return "UNKNOWN";
        try {
            BufferedReader reader=new BufferedReader(new StringReader(Preferences.read(file,4096)));
            for(String line;(line=reader.readLine())!=null;)if(line.startsWith(key+"=")) {
                String value=line.substring(key.length()+1);
                for(int i=0;i<value.length();i++) {
                    char c=value.charAt(i);
                    if(c!='_' && (c<'0' || c>'9') && (c<'A' || c>'Z'))
                        throw new IOException("Invalid status field");
                }
                return value.length()==0?"UNKNOWN":value;
            }
            return "UNKNOWN";
        } catch(IOException e) {
            if(!previous.equals("UNREADABLE"))Log.w("Settings","status unavailable: "+path+" "+e.getMessage());
            return "UNREADABLE";
        }
    }
}
