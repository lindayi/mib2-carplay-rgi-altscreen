package com.luka.carplay.rgd;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.CarPlayApp;
import de.audi.app.terminalmode.IContext;
import de.audi.app.terminalmode.events.*;
import java.lang.reflect.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;

public final class MapCardsTest {
    private static final CarplayBus bus=CarplayBus.getInstance();
    private static final MapCards cards=new MapCards();
    private static volatile CountDownLatch delivered;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static Field field(Class<?> type,String name) throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
    }
    static Socket connect() throws Exception {
        int old=bus.connectionGeneration();
        ServerSocket server=(ServerSocket)field(CarplayBus.class,"serverSocket").get(bus);
        Socket peer=new Socket("127.0.0.1",server.getLocalPort());
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(bus.connectionGeneration()<0 || bus.connectionGeneration()==old) {
            check(System.nanoTime()<end,"receiver accept");Thread.sleep(1);
        }
        return peer;
    }
    static void send(Socket peer,int type,String text) throws Exception {
        delivered=new CountDownLatch(1);
        byte[] payload=text.getBytes("UTF-8");
        DataOutputStream out=new DataOutputStream(peer.getOutputStream());
        out.writeInt(CarplayBus.MAGIC);out.writeInt(1);out.writeShort(type);
        out.writeByte(CarplayBus.FLAG_STICKY | CarplayBus.FLAG_REPLAY);out.writeByte(0);
        out.writeInt(payload.length);out.write(payload);out.flush();
        check(delivered.await(3,TimeUnit.SECONDS),"observer completes");
    }
    static MapCards.Snapshot copy(){return cards.copy(bus.connectionGeneration(),true,true,true);}
    static TrackDataChangedEvent track(String title){return new TrackDataChangedEvent(title,123000,"Album","Artist","","");}
    public static void main(String[] args) throws Exception {
        Object phone=java.lang.reflect.Proxy.newProxyInstance(IContext.class.getClassLoader(),
            new Class<?>[]{IContext.class},(proxy,method,argv)->null);
        field(CarPlayApp.class,"phoneContext").set(null,phone);
        field(CarplayBus.class,"port").setInt(bus,0);
        CarplayBus.Observer after=(generation,type,flags,payload,len)->delivered.countDown();
        Socket peer=null;
        try {
            cards.start();
            bus.addObserver(CarplayBus.EVT_RGD_UPDATE,after);
            bus.addObserver(CarplayBus.EVT_COVERART,after);
            bus.start();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(field(CarplayBus.class,"serverSocket").get(bus)==null) {
                check(System.nanoTime()<end,"bus start");Thread.sleep(1);
            }
            peer=connect();
            check(!copy().media && !copy().trip,"no fabricated initial data");
            cards.track(track("Song"),false);check(!copy().media,"non-CarPlay media excluded");
            cards.track(track("Song"),true);
            long firstTrack=copy().trackRevision;
            check(firstTrack>0,"track identity published");
            cards.track(track("Song"),true);
            check(copy().trackRevision==firstTrack,"duplicate metadata retains scroll origin");
            cards.playback(new PlaybackInfoChangedEvent(PlaybackInfoChangedEvent.PlaybackState.PAUSED),true);
            check(copy().media && copy().playback.equals("Paused"),"paused title retained without inventing playback");
            send(peer,CarplayBus.EVT_COVERART,"crc:n:999\npath:s:/var/app/icab/tmp/37/coverart.png\n");
            check(copy().art==0,"original-input CRC cannot identify generated PNG");
            send(peer,CarplayBus.EVT_COVERART,"crc:n:999\npng_crc:n:123\npath:s:/var/app/icab/tmp/37/coverart.png\n");
            check(copy().art==123,"fresh artwork event associated with known track");
            check(copy().trackRevision==firstTrack,"playback and artwork do not replace track");
            cards.track(new TrackDataChangedEvent("Song",123000,"Another album","Artist","",""),true);
            check(copy().trackRevision>firstTrack && copy().art==0,"album-only change replaces track");
            firstTrack=copy().trackRevision;
            cards.track(new TrackDataChangedEvent("Song",124000,"Another album","Artist","",""),true);
            check(copy().trackRevision>firstTrack,"changed duration replaces indistinguishable labels");
            String prefix=new String(new char[150]).replace('\0','a');
            cards.track(track(prefix+"A"),true);
            firstTrack=copy().trackRevision;
            String clipped=MapCards.snapshot(true,false).title;
            cards.track(track(prefix+"B"),true);
            check(copy().trackRevision>firstTrack && MapCards.snapshot(true,false).title.equals(clipped),
                "track token detects replacement beyond transport-clipped labels");
            cards.track(track("New song"),true);check(copy().art==0,"new track never reuses prior artwork");
            cards.track(track("Bad\ud800"),true);
            check(MapCards.snapshot(true,false).title.equals(""),"malformed Unicode omitted, not replaced with fabricated text");
            cards.track(track("Caf\u00e9"),true);
            check(MapCards.snapshot(true,false).title.equals("Caf\u00e9"),"media Unicode preserved");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"route_state:n:1\n");
            check(!copy().trip,"no empty trip card before any usable trip value");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"route_generation:n:4\nroute_state:n:1\nsource_name:s:Maps\n"
                +"destination:s:A\neta_seconds:n:1900000000\ndist_dest_m:n:10000\n"
                +"time_remaining_seconds:n:900\ndestination_timezone_minutes:n:-300\n");
            check(copy().trip && copy().progress==0 && copy().zone==-300,"first observed route baseline");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"dist_dest_m:n:7500\n");
            check(copy().progress==250,"distance-based progress, not maneuver progress");
            firstTrack=copy().trackRevision;
            check(!cards.copy(bus.connectionGeneration(),true,false,false).trip,"independent Off");
            check(copy().progress==250,"toggle does not reset baseline");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"visible_in_app:n:0\n");
            check(copy().progress==250,"main MMI departure does not reset route");
            check(copy().trackRevision==firstTrack,"route, toggles and View do not replace media");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"dist_dest_m:n:11000\n");
            check(copy().progress==0,"reroute can move backward, never negative");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"dist_dest_m:n:0\n");
            check(copy().progress==1000,"zero remaining distance reaches estimate endpoint");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"dist_dest_m:n:-1\n");
            check(copy().progress==-1,"unknown remaining hides progress");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"destination:s:B\ndist_dest_m:n:6000\n");
            check(copy().progress==0 && copy().zone==32767 && copy().eta==-1,"new destination clears time/zone/baseline");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"route_generation:n:5\ndist_dest_m:n:5000\n");
            check(copy().progress==0,"new route generation resets baseline");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"route_state:n:0\n");
            check(!copy().trip,"route end hides card");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"route_state:n:1\ndist_dest_m:n:0\n");
            check(copy().progress==-1,"no invented baseline for zero-length observed route");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"source_supports_rg:n:0\ndist_dest_m:n:100\n");
            check(!copy().trip,"unsupported source cannot resurrect trip");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"route_state:n:1\ndist_dest_m:n:100\n");
            check(!copy().trip,"unsupported source remains gated across deltas");
            send(peer,CarplayBus.EVT_RGD_UPDATE,"source_supports_rg:n:1\nroute_state:n:1\ndist_dest_m:n:100\ntime_remaining_seconds:n:90\n");
            long sampled=copy().sampled;
            Thread.sleep(1050);
            send(peer,CarplayBus.EVT_RGD_UPDATE,"time_remaining_seconds:n:90\n");
            check(copy().sampled==sampled,"duplicate replay never rejuvenates duration sample");
            int old=bus.connectionGeneration();
            peer.close();peer=connect();
            check(!copy().media && !copy().trip,"receiver replacement drops all old metadata");
            byte[] stale="route_state:n:1\ndist_dest_m:n:100\n".getBytes("UTF-8");
            cards.onFrame(old,CarplayBus.EVT_RGD_UPDATE,0,stale,stale.length);
            check(!copy().trip,"old receiver callback rejected");
            cards.track(track("Last song"),true);
            cards.resetSession();check(!copy().media,"phone boundary clears track");
            cards.stop();
            cards.track(track("Late song"),true);
            check(!copy().media,"late component callback rejected");
            cards.start();cards.track(track("Last song"),true);
            check(copy().trackRevision>firstTrack,"component restart cannot recycle observed track token");
            check(MapCards.clean(" \nA\tB\r ").equals("A B"),"controls normalized without file I/O");
        } finally {
            cards.stop();if(peer!=null)peer.close();bus.stop();
            field(CarPlayApp.class,"phoneContext").set(null,null);
        }
        System.out.println("MapCardsTest: real bus, media, baseline/reset, stale callbacks, no-route, toggles PASS");
    }
}
