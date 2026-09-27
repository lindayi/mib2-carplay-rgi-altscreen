package com.luka.carplay.rgd;

import com.luka.carplay.framework.Log;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

public final class RendererServerTransportTest {
    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    private static Field field(String name) throws Exception {
        Field f = RendererServer.class.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object get(RendererServer s, String name) throws Exception { return field(name).get(s); }
    private static void set(RendererServer s, String name, Object v) throws Exception { field(name).set(s, v); }
    private static void event(RendererServer s, Socket peer, int event) throws Exception {
        Method m = RendererServer.class.getDeclaredMethod("handleRendererEvent", Socket.class, Byte.TYPE);
        m.setAccessible(true); m.invoke(s, peer, Byte.valueOf((byte)event));
    }
    private static void ready(RendererServer server) throws Exception {
        long end = System.nanoTime() + 3000000000L;
        while (!server.isReady() && System.nanoTime() < end) Thread.sleep(1);
        check(server.isReady(), "renderer handshake");
    }
    private static void multipleClears() throws Exception {
        RendererServer server = new RendererServer(0);
        check(server.connect(), "listen");
        int port = ((ServerSocket)get(server, "server")).getLocalPort();
        Socket peer = new Socket("127.0.0.1", port); peer.setSoTimeout(3000);
        byte[] ready = new byte[48]; ready[0] = (byte)0x81;
        peer.getOutputStream().write(ready); ready(server);
        Socket owned = (Socket)get(server, "sock");
        check(server.sendVisibleArea(59, 27, 210, 153), "visible-area command");
        check(server.sendClear(), "clear 1");
        check(server.sendManeuver(3, -1, -90, 0, new int[] {-45, 90}, 8, 1, 1), "maneuver");
        check(server.sendClear(), "clear 2");
        DataInputStream in = new DataInputStream(peer.getInputStream());
        byte[] viewport = new byte[48], one = new byte[48], maneuver = new byte[48], two = new byte[48];
        in.readFully(viewport);
        check(viewport[0] == 8 && viewport[2] == 0 && viewport[3] == 59
            && viewport[5] == 27 && (viewport[7] & 255) == 210 && (viewport[9] & 255) == 153,
            "visible area uses four BE u16 in its own 48-byte command");
        in.readFully(one); in.readFully(maneuver); in.readFully(two);
        check(one[0] == 7 && two[0] == 7 && maneuver[0] == 1, "48-byte command ordering");
        check(maneuver[3] == -1 && maneuver[4] == -1 && (maneuver[5] & 255) == 166, "signed BE16 direction/angle");
        check(maneuver[7] == 2 && maneuver[45] == 1 && maneuver[46] == 8 && maneuver[47] == 1, "layout/bargraph wire fields");
        event(server, owned, 0x83);
        event(server, owned, 0x82);
        check(!server.isFrameReady(), "first CLEAR ACK exposed pixels before second CLEAR");
        event(server, owned, 0x83);
        event(server, owned, 0x82);
        check(server.isFrameReady(), "last CLEAR ACK did not release gate");
        server.dispose(); peer.close();
    }
    private static void queueOverflow() throws Exception {
        RendererServer server = new RendererServer(0);
        Socket fake = new Socket(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        set(server, "running", Boolean.TRUE); set(server, "lifecycleGeneration", Integer.valueOf(2));
        set(server, "sock", fake); set(server, "out", bytes);
        check(server.sendClear(), "queued CLEAR");
        for (int i = 0; i < 32; ++i) check(server.sendProgress(i % 17,1,RendererServer.PROGRESS_FILL), "queue fill");
        check(((Integer)get(server, "writeCount")).intValue() == 2, "coalesced progress after CLEAR");
        check(((Integer)get(server, "pendingClears")).intValue() == 1, "overflow lost CLEAR barrier");
        Object first = ((Object[])get(server, "writeQueue"))[((Integer)get(server, "writeHead")).intValue()];
        Field packet = first.getClass().getDeclaredField("packet"); packet.setAccessible(true);
        check(((byte[])packet.get(first))[0] == 7, "queue dropped or reordered CLEAR");
        event(server, fake, 0x82); check(!server.isFrameReady(), "overflow released CLEAR gate");
        Method oldWriter = RendererServer.class.getDeclaredMethod("writerLoop", Integer.TYPE);
        oldWriter.setAccessible(true); oldWriter.invoke(server, Integer.valueOf(1));
        check(((Integer)get(server, "writeCount")).intValue() == 2 && bytes.size() == 0, "old writer consumed new queue");
        // Replace every ordinary queued command with a CLEAR. With no droppable
        // command left, report backpressure without inventing another pending ACK.
        for (int i = 0; i < 31; ++i) check(server.sendClear(), "fill remaining barriers");
        check(!server.sendClear() && !server.sendProgress(1,1,RendererServer.PROGRESS_FILL), "barrier-only queue accepted overflow");
        check(((Integer)get(server, "pendingClears")).intValue() == 32, "rejected command changed ACK count");
        server.dispose();
    }
    private static void maneuverQueue() throws Exception {
        RendererServer server = new RendererServer(0);
        set(server,"running",Boolean.TRUE);set(server,"sock",new Socket());
        set(server,"out",new ByteArrayOutputStream());
        check(server.sendManeuver(3,-1,-90,0,null,0,0,1),"old maneuver");
        for(int i=0;i<80;i++)check(server.sendProgress(i%17,1,RendererServer.PROGRESS_FILL),"old progress");
        check(server.sendClear(),"transition CLEAR");
        check(server.sendManeuver(3,1,90,0,null,0,0,1),"new maneuver");
        for(int i=0;i<80;i++)check(server.sendProgress(i%17,1,RendererServer.PROGRESS_FILL),"new progress");
        Object[] q=(Object[])get(server,"writeQueue");int head=((Integer)get(server,"writeHead")).intValue();
        check(((Integer)get(server,"writeCount")).intValue()==5,"progress crossed maneuver/CLEAR boundary");
        int[] expected={1,6,7,1,6};
        for(int i=0;i<expected.length;i++) {
            Field f=q[(head+i)%q.length].getClass().getDeclaredField("packet");f.setAccessible(true);
            byte[] packet=(byte[])f.get(q[(head+i)%q.length]);
            check(packet[0]==expected[i],"accepted maneuver/order lost under progress pressure");
            if(packet[0]==6)check((packet[2]&255)==79%17,"latest progress lost");
        }
        Method clear=RendererServer.class.getDeclaredMethod("clearWriteQueue");clear.setAccessible(true);clear.invoke(server);
        for(int i=0;i<31;i++)check(server.sendManeuver(3,1,90,0,null,0,0,1),"critical queue fill");
        check(server.sendProgress(9,1,RendererServer.PROGRESS_FILL),"latest state at queue end");
        check(!server.sendVisibleArea(59,27,210,153),"viewport dropped current progress");
        check(server.sendManeuver(3,-1,-90,0,null,0,0,1),"new maneuver can supersede old progress");
        check(!server.sendManeuver(3,1,90,0,null,0,0,1),"full maneuver queue must backpressure");
        check(!server.sendProgress(9,1,RendererServer.PROGRESS_FILL),"progress evicted accepted maneuver");
        check(((Integer)get(server,"writeCount")).intValue()==32,"bounded critical queue");
        server.dispose();
    }
    private static void restart() throws Exception {
        RendererServer server = new RendererServer(0);
        for (int i = 0; i < 30; ++i) {
            check(server.connect(), "restart bind");
            Thread accept = (Thread)get(server, "acceptThread"), writer = (Thread)get(server, "writerThread");
            server.dispose();
            check(!accept.isAlive() && !writer.isAlive(), "old server workers alive");
            check(get(server, "server") == null && !server.isConnected(), "disposed server retained socket");
        }
    }
    private static void laneQueue() throws Exception {
        RendererServer server=new RendererServer(0);
        set(server,"running",Boolean.TRUE);set(server,"sock",new Socket());
        set(server,"out",new ByteArrayOutputStream());
        LaneGuidanceSnapshot shown=LaneGuidanceSnapshot.copy(73,true,true,1,
            new int[]{0},new int[]{45},new int[]{2},new int[][]{{0,45}});
        check(server.sendLaneGuidance(shown),"show lane enqueue");
        check(server.sendClear(),"lane queue CLEAR barrier");
        check(server.sendLaneGuidance(LaneGuidanceSnapshot.HIDDEN),"hide enqueue");
        for(int i=0;i<80;i++)check(server.sendProgress(i%17,1,RendererServer.PROGRESS_FILL),"lane queue progress pressure");
        Object[] q=(Object[])get(server,"writeQueue");
        int head=((Integer)get(server,"writeHead")).intValue(),n=((Integer)get(server,"writeCount")).intValue();
        int lanes=0,clears=0;
        for(int i=0;i<n;i++) {
            Object entry=q[(head+i)%q.length];Field f=entry.getClass().getDeclaredField("packet");f.setAccessible(true);
            byte[] b=(byte[])f.get(entry);
            if(b[0]==7)clears++;
            if(b[0]==12) {
                lanes++;check(clears==1 && b.length==96 && b[8]==0 && b[48]==14,"hide lost, split or crossed CLEAR");
            }
        }
        check(lanes==1 && clears==1,"queue pressure lost lane state/barrier");
        check(server.sendLaneGuidance(shown),"reappearance under queue pressure");
        head=((Integer)get(server,"writeHead")).intValue();n=((Integer)get(server,"writeCount")).intValue();
        Object tail=q[(head+n-1)%q.length];Field f=tail.getClass().getDeclaredField("packet");f.setAccessible(true);
        byte[] b=(byte[])f.get(tail);
        check(b[0]==12 && b.length==144 && b[8]==1 && b[48]==13 && b[96]==14,"latest atomic lane state lost");
        server.dispose();
    }
    public static void main(String[] args) throws Exception {
        Log.setLevel(-1); multipleClears(); queueOverflow(); maneuverQueue(); laneQueue(); labelQueue(); optionsQueue(); restart();
        System.out.println("RendererServerTransportTest: CLEAR ACK ordering, queue overflow, atomic lane hide/reappear under progress pressure, 48-byte wire, stale writer and 30 restarts PASS");
    }
    private static void labelQueue() throws Exception {
        RendererServer server=new RendererServer(0);
        set(server,"running",Boolean.TRUE);set(server,"sock",new Socket());
        set(server,"out",new ByteArrayOutputStream());
        check(server.sendManeuver(2,1,90,0,null,0,0,1),"first label maneuver");
        for(int i=0;i<80;i++)check(server.sendRouteLabels(i+" m","First Street"),"coalesced labels");
        check(server.sendClear(),"label clear barrier");
        check(server.sendManeuver(2,-1,-90,0,null,0,0,1),"next label maneuver");
        check(server.sendRouteLabels("500 ft","Next Street"),"new labels");
        Object[] q=(Object[])get(server,"writeQueue");
        int head=((Integer)get(server,"writeHead")).intValue();
        int[] expected={1,15,7,1,15};
        check(((Integer)get(server,"writeCount")).intValue()==expected.length,"bounded labels without crossing barriers");
        for(int i=0;i<expected.length;i++) {
            Field f=q[(head+i)%q.length].getClass().getDeclaredField("packet");f.setAccessible(true);
            check(((byte[])f.get(q[(head+i)%q.length]))[0]==expected[i],"label ordering");
        }
        server.disconnectClient();
        check(((Integer)get(server,"writeCount")).intValue()==0,"disconnect discards stale labels");
        server.dispose();
    }
    private static void optionsQueue() throws Exception {
        java.nio.file.Path root=java.nio.file.Files.createTempDirectory("renderer-options");
        java.lang.reflect.Constructor<?> ctor=com.luka.carplay.settings.Preferences.class.getDeclaredConstructor(java.io.File.class,java.io.File.class);
        ctor.setAccessible(true);
        com.luka.carplay.settings.Preferences preferences=(com.luka.carplay.settings.Preferences)ctor.newInstance(
            root.resolve("preferences").toFile(),root.resolve("legacy").toFile());
        Field singleton=com.luka.carplay.settings.Preferences.class.getDeclaredField("instance");singleton.setAccessible(true);
        Object previous=singleton.get(null);singleton.set(null,preferences);
        RendererServer server=new RendererServer(0);
        try {
            set(server,"running",Boolean.TRUE);set(server,"sock",new Socket());set(server,"out",new ByteArrayOutputStream());
            preferences.set(com.luka.carplay.settings.Setting.DISTANCE,0);
            preferences.set(com.luka.carplay.settings.Setting.LANES,0);
            preferences.set(com.luka.carplay.settings.Setting.TEXT_SIZE,1);
            preferences.set(com.luka.carplay.settings.Setting.ROAD_SCROLL,1);
            check(server.sendDisplayOptions() && server.sendDisplayOptions(),"options send/cache");
            check(((Integer)get(server,"writeCount")).intValue()==1,"duplicate options");
            Object[] q=(Object[])get(server,"writeQueue");
            Field packet=q[0].getClass().getDeclaredField("packet");packet.setAccessible(true);
            byte[] bytes=(byte[])packet.get(q[0]);
            check(bytes[0]==16 && bytes[2]==10 && bytes[3]==1 && bytes[4]==1 && bytes[5]==1,"option wire fields");
            server.sendClear();
            preferences.set(com.luka.carplay.settings.Setting.TEXT_SIZE,0);
            check(server.sendDisplayOptions(),"options after CLEAR");
            check(((Integer)get(server,"writeCount")).intValue()==2,"latest global options coalesced");
            server.disconnectClient();
            set(server,"sock",new Socket());set(server,"out",new ByteArrayOutputStream());
            check(server.sendDisplayOptions() && ((Integer)get(server,"writeCount")).intValue()==1,"options replay on reconnect");
        } finally {
            server.dispose();singleton.set(null,previous);
            try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{java.nio.file.Files.delete(p);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
            }
        }
    }
}
