package com.luka.carplay.settings;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.rgd.MapCards;
import java.io.*;

/** Atomic bounded snapshots on QNX4 RAM, published only by the settings worker. */
public final class MapCardControl {
    static final int MAX_SNAPSHOT=2048;
    static final int CONTROL_LEASE_MS=4000;
    static final int TEXT_FIELDS=6;
    static final String CONTROL="/ramdisk/carplay_cards.control";
    private MapCardControl(){}
    private static String hex(String text) throws IOException {
        byte[] bytes=text.getBytes("UTF-8");
        if(bytes.length>MapCards.TEXT_BYTES)throw new IOException("Map card text exceeds protocol bound");
        StringBuffer out=new StringBuffer();
        String digits="0123456789abcdef";
        for(int i=0;i<bytes.length;i++)out.append(digits.charAt((bytes[i]&255)>>4)).append(digits.charAt(bytes[i]&15));
        return out.toString();
    }
    static String encode(MapCards.Snapshot snapshot,int pid,long expiry) throws IOException {
        StringBuffer out=new StringBuffer("CARDS1 ");
        out.append(pid).append(' ').append(expiry).append(' ').append(Math.max(0,snapshot.connection)).append(' ')
            .append(snapshot.media?1:0).append(' ').append(snapshot.trip?1:0).append(' ')
            .append(snapshot.progress).append(' ').append(snapshot.art).append('\n');
        String[] lines={snapshot.title,snapshot.artist,snapshot.playback,
            snapshot.tripText[0],snapshot.tripText[1],snapshot.tripText[2]};
        if(lines.length!=TEXT_FIELDS)throw new IOException("Incorrect map card text fields");
        for(int i=0;i<lines.length;i++)out.append(hex(lines[i])).append('\n');
        return out.toString();
    }
    public static String publish(boolean media,boolean trip) throws IOException {
        File owner=new File("/tmp/MMI-Cockpit-Carplay.mirror.pid");
        int pid=0;
        if(owner.isFile()) {
            try {pid=Integer.parseInt(Preferences.read(owner,32).trim());}
            catch(NumberFormatException e){throw new IOException("Invalid map-card mirror owner");}
        }
        File file=new File(CONTROL);
        if(pid<=1 || !new File("/proc/"+pid).isDirectory()) {
            if(file.exists() && !file.delete())throw new IOException("Cannot withdraw map cards");
            return "NOT_RUNNING";
        }
        MapCards.Snapshot snapshot=MapCards.snapshot(media,trip);
        byte[] bytes=encode(snapshot,pid,System.currentTimeMillis()+CONTROL_LEASE_MS).getBytes("US-ASCII");
        if(bytes.length>MAX_SNAPSHOT)throw new IOException("Map cards snapshot exceeds protocol bound");
        File temporary=new File(CONTROL+".new");
        FileOutputStream output=new FileOutputStream(temporary);
        try {output.write(bytes);output.flush();}finally{output.close();}
        if(snapshot.connection>=0 && snapshot.connection!=CarplayBus.getInstance().connectionGeneration()) {
            if(!temporary.delete())throw new IOException("Cannot discard stale map-card snapshot");
            return "SESSION_CHANGED";
        }
        if(!temporary.renameTo(file))throw new IOException("Cannot publish map cards on /ramdisk");
        return snapshot.media || snapshot.trip?"CONTROL_PUBLISHED":media || trip?"WAITING_DATA":"OFF";
    }
}
