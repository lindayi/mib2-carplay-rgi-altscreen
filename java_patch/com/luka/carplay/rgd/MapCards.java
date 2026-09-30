package com.luka.carplay.rgd;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.CarPlayApp;
import com.luka.carplay.framework.Log;
import de.audi.app.terminalmode.events.TrackDataChangedEvent;
import de.audi.app.terminalmode.events.PlaybackInfoChangedEvent;

/** Passive per-component metadata cache. Callbacks never touch files, BAP or GL. */
public final class MapCards implements CarplayBus.Observer {
    public static final int TEXT_BYTES=128;
    private static MapCards active;
    private static BAPBridge formatter;
    private boolean running;
    private int connection=-1,routeState=-1,sourceSupportsRg=-1,meters=-1,baseline=-1;
    private long routeGeneration=-1,eta=-1,remaining=-1,sampled=-1,art;
    private int zone=RouteGuidance.State.UNKNOWN_TIMEZONE;
    private String destination,source,title="",artist="",album="",playback="";

    public void start() {
        synchronized(this){running=true;reset(-1);}
        synchronized(MapCards.class){active=this;}
        CarplayBus bus=CarplayBus.getInstance();
        bus.addObserver(CarplayBus.EVT_RGD_UPDATE,this);
        bus.addObserver(CarplayBus.EVT_COVERART,this);
    }
    public void stop() {
        synchronized(this){running=false;reset(-1);}
        CarplayBus bus=CarplayBus.getInstance();
        bus.removeObserver(CarplayBus.EVT_RGD_UPDATE,this);
        bus.removeObserver(CarplayBus.EVT_COVERART,this);
        synchronized(MapCards.class){if(active==this)active=null;}
    }
    public synchronized void resetSession(){reset(-1);}
    private void clearTrip() {
        meters=baseline=-1;eta=remaining=sampled=-1;destination=null;
        zone=RouteGuidance.State.UNKNOWN_TIMEZONE;
    }
    private void reset(int next) {
        connection=next;routeState=sourceSupportsRg=-1;routeGeneration=-1;source=null;clearTrip();
        title=artist=album=playback="";art=0;
    }
    private boolean accept(int generation) {
        if(!running || generation<0 || generation!=CarplayBus.getInstance().connectionGeneration()
                || !CarPlayApp.isSessionConnected())return false;
        if(connection!=generation)reset(generation);
        return true;
    }
    public synchronized void track(TrackDataChangedEvent track,boolean carplay) {
        int generation=CarplayBus.getInstance().connectionGeneration();
        if(!carplay || !accept(generation))return;
        String nextTitle=clean(track.getTitle()),nextArtist=clean(track.getArtist()),nextAlbum=clean(track.getAlbum());
        if(!title.equals(nextTitle) || !artist.equals(nextArtist) || !album.equals(nextAlbum))art=0;
        title=nextTitle;artist=nextArtist;album=nextAlbum;
    }
    public synchronized void playback(PlaybackInfoChangedEvent info,boolean carplay) {
        int generation=CarplayBus.getInstance().connectionGeneration();
        if(!carplay || !accept(generation))return;
        PlaybackInfoChangedEvent.PlaybackState state=info.getState();
        playback=state==PlaybackInfoChangedEvent.PlaybackState.PLAYING?"Playing":
            state==PlaybackInfoChangedEvent.PlaybackState.PAUSED?"Paused":
            state==PlaybackInfoChangedEvent.PlaybackState.STOPPED?"Stopped":"";
    }
    public synchronized void onFrame(int generation,int type,int flags,byte[] payload,int len) {
        if(!accept(generation))return;
        CarplayBus.Data data=CarplayBus.parseText(payload,len);
        if(type==CarplayBus.EVT_COVERART) {
            long crc=data.num64("png_crc",0);
            if((title.length()!=0 || artist.length()!=0) && crc>0 && crc<=4294967295L
                    && com.luka.carplay.coverart.CoverArt.COVERART_PATH.equals(data.str("path")))art=crc;
        } else if(type==CarplayBus.EVT_RGD_UPDATE)route(data,System.currentTimeMillis()/1000L);
    }
    private static boolean different(String before,String after){return before!=null && !before.equals(after);}
    void route(CarplayBus.Data data,long nowSeconds) {
        if(data.has("disconnect_reason")){reset(connection);return;}
        long next=data.num64("route_generation",routeGeneration);
        String nextSource=data.str("source_name",source);
        String nextDestination=data.str("destination",destination);
        boolean newRoute=next>=0 && next!=routeGeneration;
        boolean newDestination=different(destination,nextDestination);
        boolean newSource=different(source,nextSource);
        if(newRoute || newSource || newDestination)clearTrip();
        if(newSource)sourceSupportsRg=-1;
        if(newRoute)routeGeneration=next;
        source=nextSource;
        if(data.has("route_state"))routeState=data.num("route_state",-1);
        if(data.has("source_supports_rg"))sourceSupportsRg=data.num("source_supports_rg",-1);
        if(sourceSupportsRg==0)routeState=0;
        if(routeState!=1 && routeState!=6) {
            if(routeState==0){clearTrip();return;}
            return;
        }
        if(data.has("destination"))destination=nextDestination;
        if(data.has("dist_dest_m"))meters=data.num("dist_dest_m",-1);
        if(data.has("eta_seconds"))eta=data.num("eta_seconds",-1);
        if(data.has("time_remaining_seconds")) {
            long value=data.num64("time_remaining_seconds",-1);
            if(value>4294967295L) {
                Log.w("MapCards","invalid remaining duration");
                value=-1;
            }
            if(value!=remaining){remaining=value;sampled=remaining>=0?nowSeconds:-1;}
        }
        if(data.has("destination_timezone_minutes")) {
            int value=data.num("destination_timezone_minutes",RouteGuidance.State.UNKNOWN_TIMEZONE);
            if(value!=RouteGuidance.State.UNKNOWN_TIMEZONE && (value< -840 || value>840))
                Log.w("MapCards","invalid destination timezone; using HU time");
            zone=value>= -840 && value<=840?value:RouteGuidance.State.UNKNOWN_TIMEZONE;
        }
        if(baseline<0 && meters>0)baseline=meters;
    }
    static String clean(String value) {
        if(value==null)return "";
        StringBuffer out=new StringBuffer();
        int end=Math.min(value.length(),512);
        if(end<value.length() && end>0 && value.charAt(end-1)>=0xd800 && value.charAt(end-1)<=0xdbff)end--;
        for(int i=0;i<end;i++) {
            char c=value.charAt(i);
            out.append(c<32 || c==127?' ':c);
        }
        return out.toString().trim();
    }
    private static String normalized(String text) {
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(c>=0xd800 && c<=0xdbff) {
                if(i+1<text.length() && text.charAt(i+1)>=0xdc00 && text.charAt(i+1)<=0xdfff){i++;continue;}
            } else if(c<0xdc00 || c>0xdfff)continue;
            Log.w("MapCards","invalid Unicode media field omitted");
            return "";
        }
        return RouteLabels.clip(text,TEXT_BYTES);
    }
    public static final class Snapshot {
        public int connection=-1,progress=-1,meters=-1,zone=RouteGuidance.State.UNKNOWN_TIMEZONE;
        public long art,eta=-1,remaining=-1,sampled=-1;
        public boolean media,trip;
        public String title="",artist="",playback="";
        public String[] tripText={"","",""};
    }
    synchronized Snapshot copy(int generation,boolean phone,boolean mediaEnabled,boolean tripEnabled) {
        Snapshot out=new Snapshot();
        if(!running || generation<0 || !phone || connection!=generation) {
            reset(generation);return out;
        }
        out.connection=generation;
        out.media=mediaEnabled && (title.length()!=0 || artist.length()!=0);
        out.trip=tripEnabled && (routeState==1 || routeState==6) && (meters>=0 || eta>=0 || remaining>=0);
        if(out.media){out.title=title;out.artist=artist;out.playback=playback;out.art=art;}
        if(out.trip) {
            out.meters=meters;out.zone=zone;out.eta=eta;out.remaining=remaining;out.sampled=sampled;
            if(baseline>0 && meters>=0)out.progress=(int)Math.max(0L,Math.min(1000L,
                ((long)baseline-meters)*1000L/baseline));
        }
        return out;
    }
    /** Only the settings worker formats values; native callbacks merely cache metadata. */
    public static Snapshot snapshot(boolean media,boolean trip) {
        MapCards owner;
        synchronized(MapCards.class){owner=active;}
        if(owner==null)return new Snapshot();
        Snapshot out=owner.copy(CarplayBus.getInstance().connectionGeneration(),
            CarPlayApp.isSessionConnected(),media,trip);
        out.title=normalized(out.title);
        out.artist=normalized(out.artist);
        if(out.trip) {
            if(formatter==null)formatter=new BAPBridge();
            // Translate sample age on the worker; callbacks never query stock HMI services.
            if(out.sampled>=0)out.sampled=BAPBridge.getUtcMillis()/1000L
                -Math.max(0L,System.currentTimeMillis()/1000L-out.sampled);
            out.tripText=formatter.formatTripCard(out.meters,out.eta,out.remaining,out.sampled,out.zone);
        }
        return out;
    }
}
