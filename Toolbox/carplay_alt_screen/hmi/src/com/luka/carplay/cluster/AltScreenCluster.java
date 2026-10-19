/*
 * AltScreenCluster — commands for the CarPlay instrument-cluster display that the
 * AltScreen mirror shows on the VC (see AltScreenVideo).
 *
 *   Steering-wheel roller -> CMD_ALT_ZOOM: while the CarPlay video is on the cluster,
 *   each stock MapScale step also zooms the iPhone's cluster map (the hook sends the
 *   AirPlay "changeMapZoomLevel" command).  Stock still zooms its own hidden map.
 *
 *   Cluster UI context -> CMD_ALT_UICTX: if /mnt/app/root/hooks/cluster_ui.url holds a
 *   maps:/car/instrumentcluster URL, it is sent as "showUI" when video comes up and
 *   when a route settles/changes or the VC changes map size. The screen worker performs I/O;
 *   route callbacks only publish intent, independently of the BAP/renderer mode.
 */
package com.luka.carplay.cluster;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.framework.Log;
import com.luka.carplay.settings.Preferences;
import com.luka.carplay.settings.Setting;
import com.luka.carplay.settings.ReceiverSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public final class AltScreenCluster {

    private static final String TAG = "AltCluster";
    public static final String UI_URL_FILE = "/mnt/app/root/hooks/cluster_ui.url";
    private static final String MAP_URL = "maps:/car/instrumentcluster/map";
    private static final String CARD_URL = "maps:/car/instrumentcluster";
    private static final int MAX_URL = 400;

    /* Host tests point this at a scratch file. */
    private static String uiUrlPath = UI_URL_FILE;

    private static final Object LOCK = new Object();
    private static final int VIDEO = 1, ROUTE = 2, VIEW = 4, MMI_RETURN = 8, STARTUP = 16, POSITION = 32;
    private static boolean enabled, video;
    private static int connection = -1;
    private static int pending;
    private static int lifecycle;
    private static long revision;
    private static int presentation = -1;
    private static long viewChangedAt;
    private static int mainScreen = -1;
    private static long mainScreenChangedAt;
    private static int routeState = -1, sourceSupportsRg = -1;
    private static long routeGeneration = -1L;
    private static String sourceName;
    /* Keep the applied layout across module restarts; saved changes require a receiver reconnect. */
    private static int urlConnection = -1;
    private static String sessionUrl;
    private static int sessionLayout=-1,sessionEta=-1,sessionPhoneSign=-1;
    private static CarplayBus.Observer routeObserver;
    private static CarplayBus.Observer sessionObserver;
    private static boolean startupAllowed, suppressInitialVideo;
    private static long nativeSession, startupSentSession;
    private static long nativeReceiverPid;
    public static final int HMI_LEASE_MS=1500;
    private static String hmiPath="/ramdisk/mmi-mirror-hmi.state";
    private static final Object HMI_LOCK=new Object();
    private static String lastHmiBody,lastHmiError;
    private static long lastHmiWrite,lastHmiWarning;
    private static File phoneSessionFile=new File("/ramdisk/carplay_menu_session");

    private AltScreenCluster() {}

    private static boolean cardLayout(int layout) { return layout==0 || layout==1 || layout==4; }
    private static String cardPosition(int layout) {
        return layout==1?"rightaligned":layout==4?"leftaligned":"topaligned";
    }
    private static String uiUrl(int layout,int phoneSign,int eta) {
        StringBuffer url=new StringBuffer(cardLayout(layout)?CARD_URL:MAP_URL);
        url.append("?showSpeedLimit=").append(phoneSign==1?"user":"no");
        if(layout!=3)url.append("&showCompass=user");
        if(eta==0)url.append("&showETA=no");
        if(cardLayout(layout))url.append("&maneuverLayout=").append(cardPosition(layout));
        else if(layout==2)url.append("&maneuverLayout=");
        return url.toString();
    }
    private static final class UiSelection {
        final int layout,phoneSign,eta;
        final String url;
        UiSelection(int layout,int phoneSign,int eta) {
            this.layout=layout;this.phoneSign=phoneSign;this.eta=eta;
            url=uiUrl(layout,phoneSign,eta);
        }
    }

    /** Requested phone content, not a claim that the app rendered or hid its sign. */
    public static boolean phoneSignRequestedForSession() {
        int generation=CarplayBus.getInstance().connectionGeneration();
        synchronized(LOCK){return generation>=0 && urlConnection==generation && sessionPhoneSign==1;}
    }
    public static int phoneProfileForSession() {
        int generation=CarplayBus.getInstance().connectionGeneration();
        synchronized(LOCK) {
            return generation<0 || urlConnection!=generation || sessionUrl==null?-1:cardLayout(sessionLayout)?1:0;
        }
    }
    public static boolean reconnectPending(Preferences.Snapshot prefs) {
        int generation=CarplayBus.getInstance().connectionGeneration();
        synchronized(LOCK) {
            if(generation<0 || urlConnection!=generation)return false;
            if(sessionUrl==null)return true;
            int layout=prefs.get(Setting.LAYOUT);
            return sessionPhoneSign!=(prefs.get(Setting.SPEED_BADGE)==2?1:0)
                || sessionEta!=prefs.get(Setting.PHONE_ETA)
                || (sessionLayout!=layout && !(cardLayout(sessionLayout) && cardLayout(layout)));
        }
    }
    /** Worker publication: only position may change inside a latched card profile. */
    public static void updatePreferences(Preferences.Snapshot prefs) {
        int generation=CarplayBus.getInstance().connectionGeneration();
        synchronized(LOCK) {
            int layout=prefs.get(Setting.LAYOUT);
            if(!enabled || !prefs.on(Setting.ENABLED) || generation<0 || urlConnection!=generation
                    || sessionUrl==null || !cardLayout(sessionLayout) || !cardLayout(layout)
                    || sessionLayout==layout)return;
            sessionLayout=layout;
            sessionUrl=uiUrl(layout,sessionPhoneSign,sessionEta);
            pending|=POSITION;revision++;
        }
    }

    public static final class MainScreenEvent {
        private final int run, generation;
        private MainScreenEvent(int run,int generation) { this.run=run;this.generation=generation; }
    }

    /** Capture before HMI debounce so delayed events cannot affect a replacement session. */
    public static MainScreenEvent captureMainScreenEvent() {
        synchronized(LOCK) {
            return new MainScreenEvent(lifecycle,CarplayBus.getInstance().connectionGeneration());
        }
    }

    /** Accepted main-MMI action-proxy state, not RGI visible_in_app or a VC View edge. */
    public static void onMainScreen(MainScreenEvent event,boolean active) {
        synchronized(LOCK) {
            if(!enabled || event==null || event.run!=lifecycle || event.generation<0
                    || event.generation!=CarplayBus.getInstance().connectionGeneration())return;
            useConnection(event.generation);
            int next=active?1:0;
            if(mainScreen==next)return;
            int previous=mainScreen;
            mainScreen=next;
            if(!active)pending&=~MMI_RETURN;
            else if(previous==0 && video) {
                pending|=MMI_RETURN;
                mainScreenChangedAt=System.currentTimeMillis();
            }
            revision++;
        }
    }

    public static void start() {
        synchronized (LOCK) {
            if (routeObserver != null)
                CarplayBus.getInstance().removeObserver(CarplayBus.EVT_RGD_UPDATE, routeObserver);
            if (sessionObserver != null)
                CarplayBus.getInstance().removeObserver(CarplayBus.EVT_ALT_SESSION, sessionObserver);
            final int run = ++lifecycle;
            enabled = true;
            video = false;
            connection = -1;
            presentation = -1;
            mainScreen = -1;
            pending = 0;
            revision++;
            resetRoute();
            resetStartup();
            routeObserver = new CarplayBus.Observer() {
                public void onFrame(int generation, int type, int flags, byte[] payload, int len) {
                    onRouteFrame(run, generation, CarplayBus.parseText(payload, len));
                }
            };
            CarplayBus.getInstance().addObserver(CarplayBus.EVT_RGD_UPDATE, routeObserver);
            sessionObserver = new CarplayBus.Observer() {
                public void onFrame(int generation, int type, int flags, byte[] payload, int len) {
                    synchronized (LOCK) {
                        if (!enabled || run != lifecycle
                                || generation != CarplayBus.getInstance().connectionGeneration()) return;
                        useConnection(generation);
                        if((flags & CarplayBus.FLAG_BINARY)==0 || len!=CarplayBus.ALT_SESSION_PAYLOAD_SIZE
                                || payload==null || payload.length<len || unsignedInt(payload,4)<=1) {
                            nativeSession=nativeReceiverPid=startupSentSession=0;
                            pending&=~STARTUP;suppressInitialVideo=false;revision++;
                            Log.w(TAG,"invalid native session identity; readiness withdrawn");
                            return;
                        }
                        long epoch=unsignedInt(payload,0),pid=unsignedInt(payload,4);
                        if(nativeSession==epoch && nativeReceiverPid==pid)return;
                        nativeSession = epoch;
                        nativeReceiverPid=pid;
                        if (epoch == 0) { pending &= ~STARTUP; suppressInitialVideo = false; }
                        queueStartup();
                        revision++;
                    }
                }
            };
            CarplayBus.getInstance().addObserver(CarplayBus.EVT_ALT_SESSION, sessionObserver);
        }
    }

    public static void stop() {
        int stoppedRun;
        synchronized (LOCK) {
            enabled = false;
            video = false;
            pending = 0;
            lifecycle++;
            stoppedRun=lifecycle;
            revision++;
            resetRoute();
            resetStartup();
            if (routeObserver != null)
                CarplayBus.getInstance().removeObserver(CarplayBus.EVT_RGD_UPDATE, routeObserver);
            routeObserver = null;
            if (sessionObserver != null)
                CarplayBus.getInstance().removeObserver(CarplayBus.EVT_ALT_SESSION, sessionObserver);
            sessionObserver = null;
        }
        publishHmiState(false,stoppedRun);
    }

    /** Screen worker only. No file access on the BAP or bus callback threads. */
    public static void publishHmiState(boolean allowed) {
        publishHmiState(allowed,-1);
    }
    private static void publishHmiState(boolean allowed,int stoppedRun) {
        synchronized(HMI_LOCK) {
            synchronized(LOCK) {
                if(stoppedRun>=0 && (enabled || lifecycle!=stoppedRun))return;
            }
            File file=new File(hmiPath),temporary=new File(hmiPath+".new");
            try {
                String geometry=allowed?ClusterLayerController.nativeHmiState():null;
                int run,generation;long epoch,pid;
                synchronized(LOCK) {
                    run=lifecycle;generation=connection;epoch=nativeSession;pid=nativeReceiverPid;
                    if(!enabled || !startupAllowed || generation<0
                            || generation!=CarplayBus.getInstance().connectionGeneration()
                            || epoch==0 || pid<=1)geometry=null;
                }
                if(geometry==null) {
                    if(lastHmiBody!=null && file.exists() && !file.delete())
                        throw new IOException("Cannot withdraw native HMI state");
                    lastHmiBody=null;lastHmiWrite=0;
                    return;
                }
                String body=geometry+"receiver_epoch="+epoch+"\nreceiver_pid="+pid+"\n";
                long now=System.currentTimeMillis(),expiry=now+HMI_LEASE_MS;
                if(body.equals(lastHmiBody) && now>=lastHmiWrite && now-lastHmiWrite<500)return;
                FileOutputStream out=new FileOutputStream(temporary);
                try {out.write((body+"expiry_ms="+expiry+"\n").getBytes("US-ASCII"));out.flush();}
                finally {out.close();}
                synchronized(LOCK) {
                    if(!enabled || !startupAllowed || run!=lifecycle || generation!=connection
                            || epoch!=nativeSession || pid!=nativeReceiverPid
                            || generation!=CarplayBus.getInstance().connectionGeneration())return;
                }
                long remaining=expiry-System.currentTimeMillis();
                if(remaining<=0 || remaining>HMI_LEASE_MS)throw new IOException("Native HMI publication expired");
                if(!temporary.renameTo(file))throw new IOException("Cannot publish native HMI state on /ramdisk");
                lastHmiBody=body;lastHmiWrite=now;lastHmiError=null;
            } catch(IOException e) {hmiError(e.toString());}
              catch(SecurityException e) {hmiError(e.toString());}
            finally {
                try {
                    if(temporary.exists() && !temporary.delete())hmiError("Cannot remove native HMI temporary state");
                } catch(SecurityException e) {hmiError(e.toString());}
            }
        }
    }
    private static void hmiError(String message) {
        long now=System.currentTimeMillis();
        if(!message.equals(lastHmiError) || now<lastHmiWarning || now-lastHmiWarning>=30000) {
            Log.e(TAG,message);lastHmiError=message;lastHmiWarning=now;
        }
    }

    private static void resetRoute() {
        routeState = sourceSupportsRg = -1;
        routeGeneration = -1L;
        sourceName = null;
    }

    private static void useConnection(int generation) {
        if (connection == generation) return;
        connection = generation;
        presentation = -1;
        mainScreen = -1;
        resetRoute();
        resetStartup();
        pending = video && generation >= 0 ? VIDEO : 0;
        revision++;
    }

    private static void resetStartup() {
        startupAllowed = suppressInitialVideo = false;
        nativeSession = startupSentSession = 0L;
        nativeReceiverPid=0;
    }

    private static long unsignedInt(byte[] value,int at) {
        return ((long)(value[at]&255)<<24) | ((long)(value[at+1]&255)<<16)
            | ((long)(value[at+2]&255)<<8) | (value[at+3]&255);
    }

    private static void queueStartup() {
        if (startupAllowed && nativeSession != 0L && nativeSession != startupSentSession
                && (pending & STARTUP) == 0) {
            pending |= STARTUP;
            revision++;
        }
    }

    /** OEM and native command-channel readiness must both precede initial showUI. */
    public static void setStartupAllowed(int generation, boolean allowed) {
        synchronized (LOCK) {
            if (!enabled || generation != CarplayBus.getInstance().connectionGeneration()) return;
            useConnection(generation);
            startupAllowed = allowed;
            if (!allowed && (pending & STARTUP) != 0) { pending &= ~STARTUP; revision++; }
            queueStartup();
        }
    }

    private static boolean settled(int state) { return state == 1 || state == 6; }

    private static void onRouteFrame(int run, int generation, CarplayBus.Data data) {
        synchronized (LOCK) {
            if (!enabled || run != lifecycle
                    || generation != CarplayBus.getInstance().connectionGeneration()) return;
            useConnection(generation);
            if (data.has("disconnect_reason")) {
                resetRoute();
                pending = 0;
                revision++;
                return;
            }
            boolean wasSettled = settled(routeState);
            long oldGeneration = routeGeneration;
            String oldSource = sourceName;
            if (data.has("route_generation")) {
                long next = data.num64("route_generation", -1L);
                if (next >= 0L) routeGeneration = next;
                else Log.w(TAG, "ignoring invalid route generation");
            }
            if (data.has("route_state")) routeState = data.num("route_state", -1);
            if (data.has("source_supports_rg")) sourceSupportsRg = data.num("source_supports_rg", -1);
            if (data.has("source_name")) sourceName = data.str("source_name");
            if (sourceSupportsRg == 0) routeState = 0;
            if (!settled(routeState)) {
                if ((pending & ROUTE) != 0) { pending &= ~ROUTE; revision++; }
                return;
            }
            boolean newSource = oldSource != null && sourceName != null && !oldSource.equals(sourceName);
            if (!wasSettled || routeGeneration != oldGeneration || newSource) {
                pending |= ROUTE;
                revision++;
            }
        }
    }

    /** Lightweight publication under ScreenModule's context lock; no files or socket writes. */
    public static void setVideoReady(boolean ready) {
        synchronized (LOCK) {
            if (!enabled || video == ready) return;
            video = ready;
            pending = ready ? (suppressInitialVideo ? pending : pending | VIDEO) : 0;
            if (ready) suppressInitialVideo = false;
            revision++;
        }
    }

    /** Actual Fct54 size edges only; duplicate status and drawer changes do not request layout. */
    public static void onPresentation(boolean large) {
        synchronized (LOCK) {
            if (!enabled) return;
            useConnection(CarplayBus.getInstance().connectionGeneration());
            int next = large ? 1 : 0;
            if (presentation == next) return;
            boolean edge = presentation != -1;
            presentation = next;
            if (!edge || !video || connection < 0) return;
            pending |= VIEW;
            viewChangedAt = System.currentTimeMillis();
            revision++;
        }
    }

    /** Only the persistent screen worker calls this. No successful request is retried on a timer. */
    public static void flushLayout() {
        int generation;
        long request;
        String url;
        boolean load;
        long receiverPid;
        synchronized (LOCK) {
            if (!enabled) return;
            generation = CarplayBus.getInstance().connectionGeneration();
            useConnection(generation);
            if (generation < 0 || pending == 0 || nativeSession==0 || nativeReceiverPid<=1
                    || (!video && (!startupAllowed || (pending & STARTUP) == 0))) return;
            long now = System.currentTimeMillis();
            if ((pending & VIEW) != 0 && now >= viewChangedAt && now - viewChangedAt < 350L) return;
            if ((pending & MMI_RETURN) != 0 && now >= mainScreenChangedAt
                    && now - mainScreenChangedAt < 350L) return;
            request = revision;
            load = urlConnection != generation;
            url = sessionUrl;
            receiverPid=nativeReceiverPid;
        }
        UiSelection selection=load?readSessionSelection(receiverPid):null;
        if (load) url=selection==null?null:selection.url;
        byte[] bytes = null;
        try { if (url != null) bytes = url.getBytes("UTF-8"); }
        catch (IOException e) { Log.e(TAG, "cannot encode cluster layout", e); }
        synchronized (LOCK) {
            if (!enabled || (!video && (!startupAllowed || (pending & STARTUP) == 0)) || revision != request
                    || generation != CarplayBus.getInstance().connectionGeneration()) return;
            if (load) {
                sessionUrl=url;urlConnection=generation;
                sessionLayout=selection==null?-1:selection.layout;
                sessionEta=selection==null?-1:selection.eta;
                sessionPhoneSign=selection==null?-1:selection.phoneSign;
            }
            if (bytes == null) {
                pending = 0;
                Log.w(TAG, "cluster layout unavailable; reconnect after repairing preferences");
                return;
            }
            int reasons = pending;
            String reason = (reasons & VIDEO) != 0 ? "video" : "";
            if ((reasons & ROUTE) != 0) reason += (reason.length() == 0 ? "" : "+") + "route";
            if ((reasons & VIEW) != 0) reason += (reason.length() == 0 ? "" : "+") + "view";
            if ((reasons & MMI_RETURN) != 0) reason += (reason.length() == 0 ? "" : "+") + "mmi-return";
            if ((reasons & STARTUP) != 0) reason += (reason.length() == 0 ? "" : "+") + "startup";
            if ((reasons & POSITION) != 0) reason += (reason.length() == 0 ? "" : "+") + "card-position";
            boolean sent = CarplayBus.getInstance().sendBinary(CarplayBus.CMD_ALT_UICTX, bytes, generation);
            if (sent) {
                pending = 0;
                if ((reasons & STARTUP) != 0) {
                    startupSentSession = nativeSession;
                    suppressInitialVideo = !video;
                }
            }
            Log.i(TAG, "cluster showUI " + (sent ? "queued" : "not queued")
                + " connection=" + generation
                + " reason=" + reason
                + " route_generation=" + routeGeneration + " route_state=" + routeState + " url=" + url);
        }
    }

    /** Reuse only the latched URL; enqueueing performs no socket I/O or settings reads. */
    public static void reapplyLayout() throws IOException {
        synchronized (LOCK) {
            if (!enabled || !video) throw new IOException("CarPlay cluster video is not active");
            int generation = CarplayBus.getInstance().connectionGeneration();
            if (generation < 0) throw new IOException("Receiver is not connected");
            if(nativeSession==0 || nativeReceiverPid<=1)throw new IOException("Receiver command session is not ready");
            if (urlConnection != generation || sessionUrl == null)
                throw new IOException("Session layout is not ready; reconnect if preferences were invalid");
            if (!CarplayBus.getInstance().sendBinary(CarplayBus.CMD_ALT_UICTX,
                    sessionUrl.getBytes("UTF-8"), generation))
                throw new IOException("Receiver changed; layout was not queued");
            pending = 0;
            revision++;
            Log.i(TAG, "cluster showUI queued connection=" + generation + " reason=manual url=" + sessionUrl);
        }
    }

    /** Stock CombiBAPListener.setMapScale steps (positive = zoom out). */
    public static void onMapScaleSteps(int steps) {
        if (steps == 0 || !ScreenModule.isAltScreenVideo()) return;
        Preferences.Snapshot preferences=Preferences.get().snapshot();
        if(!preferences.on(Setting.ZOOM))return;
        if(preferences.get(Setting.ZOOM_SPEED)==1) {
            if(steps>63)steps=127;else if(steps<-63)steps=-127;else steps*=2;
        }
        if (steps > 127) steps = 127;
        if (steps < -127) steps = -127;
        boolean sent = CarplayBus.getInstance().sendBinary(CarplayBus.CMD_ALT_ZOOM,
            new byte[] { (byte)steps });
        Log.i(TAG, "roller steps=" + steps + " -> cluster zoom " + (steps > 0 ? "out" : "in")
            + (sent ? "" : " (bus down)"));
    }

    static String readUiUrl() {
        UiSelection selected=readSelection();
        return selected==null?null:selected.url;
    }
    private static UiSelection readSessionSelection(long pid) {
        try {
            ReceiverSession s=ReceiverSession.read(phoneSessionFile);
            if(s.pid!=pid || s.enabled!=1 || s.video!=1 || s.configError!=0 || s.phoneLayout<0)
                throw new IOException("Receiver phone profile is missing or belongs to another session");
            return new UiSelection(s.phoneLayout,s.phoneSign,s.phoneEta);
        } catch(IOException e) {Log.w(TAG,"Cannot read exec-time phone selection: "+e);}
          catch(SecurityException e) {Log.w(TAG,"Phone session access denied: "+e);}
        return null;
    }
    private static UiSelection readSelection() {
        FileInputStream in = null;
        try {
            Preferences preferences=Preferences.get();
            if(preferences.exists()) {
                preferences.refresh();
                if(preferences.error().length()!=0)return null;
                Preferences.Snapshot s=preferences.snapshot();
                return new UiSelection(s.get(Setting.LAYOUT),s.get(Setting.SPEED_BADGE)==2?1:0,s.get(Setting.PHONE_ETA));
            }
            File f = new File(uiUrlPath);
            if (!f.exists()) return new UiSelection(0,0,0);
            long length = f.length();
            if (length <= 0 || length > MAX_URL) {
                Log.w(TAG, "ignoring " + uiUrlPath + ": invalid layout length");
                return null;
            }
            byte[] buf = new byte[(int)length];
            in = new FileInputStream(f);
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) throw new IOException("Layout file truncated during read");
                n += r;
            }
            if (in.read() != -1) throw new IOException("Layout file grew during read");
            String url = new String(buf, 0, n, "UTF-8").trim();
            if(url.equals(MAP_URL))return new UiSelection(3,0,1);
            if(url.equals(MAP_URL+"?maneuverLayout=topaligned"))return new UiSelection(0,0,1);
            if(url.equals(MAP_URL+"?maneuverLayout=rightaligned"))return new UiSelection(1,0,1);
            if(url.equals(MAP_URL+"?showETA=no"))return new UiSelection(2,0,0);
            Log.w(TAG,"ignoring "+uiUrlPath+": unsupported legacy layout URL");
            return null;
        } catch (IOException e) {
            Log.w(TAG, "cannot read cluster layout: " + e);
            return null;
        } catch (SecurityException e) {
            Log.w(TAG, "cluster layout access denied: " + e);
            return null;
        } finally {
            if (in != null) try { in.close(); }
            catch (IOException e) { Log.w(TAG, "cannot close cluster layout: " + e); }
        }
    }
}
