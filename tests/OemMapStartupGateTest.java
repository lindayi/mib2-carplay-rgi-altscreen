package com.luka.carplay.core;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.framework.Log;
import java.io.DataInputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

public final class OemMapStartupGateTest {
    private static final CarplayBus BUS = CarplayBus.getInstance();
    private static Field field(Class type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }
    private static int generation() throws Exception {
        return field(OemMapStartupGate.class, "generation").getInt(null);
    }
    private static void check(boolean value, String why) {
        if (!value) throw new AssertionError(why);
    }
    private static Socket connect() throws Exception {
        int old = BUS.connectionGeneration();
        ServerSocket server = (ServerSocket)field(CarplayBus.class, "serverSocket").get(BUS);
        Socket socket = new Socket("127.0.0.1", server.getLocalPort());
        long deadline = System.currentTimeMillis() + 3000;
        while (!BUS.isConnected() || BUS.connectionGeneration() == old) {
            check(System.currentTimeMillis() < deadline, "bus accept");
            Thread.sleep(1);
        }
        socket.setSoTimeout(2000);
        return socket;
    }
    private static void expectReady(Socket peer) throws Exception {
        DataInputStream in = new DataInputStream(peer.getInputStream());
        check(in.readInt() == CarplayBus.MAGIC, "magic");
        in.readInt();
        check(in.readUnsignedShort() == CarplayBus.CMD_OEM_MAP_READY, "readiness opcode");
        check(in.readUnsignedByte() == CarplayBus.FLAG_BINARY, "binary flag");
        in.readByte();
        check(in.readInt() == 1 && in.readUnsignedByte() == 1, "initialized payload");
    }
    private static void quiet(Socket peer) throws Exception {
        peer.setSoTimeout(80);
        try {
            peer.getInputStream().read();
            throw new AssertionError("unexpected readiness command");
        } catch (SocketTimeoutException expected) {
        } finally { peer.setSoTimeout(2000); }
    }
    public static void main(String[] args) throws Exception {
        Log.setLevel(-1);
        field(CarplayBus.class, "port").setInt(BUS, 0);
        Socket peer = null;
        try {
            BUS.start();
            long deadline = System.currentTimeMillis() + 3000;
            while (field(CarplayBus.class, "serverSocket").get(BUS) == null) {
                check(System.currentTimeMillis() < deadline, "bus listening");
                Thread.sleep(1);
            }
            peer = connect();
            OemMapStartupGate.setActive(true);
            int stale = generation();
            OemMapStartupGate.setActive(false);
            OemMapStartupGate.setActive(true);
            OemMapStartupGate.completeProbe(stale, true);
            check(!field(OemMapStartupGate.class, "ready").getBoolean(null), "stale probe discarded");
            OemMapStartupGate.completeProbe(generation(), false);
            check(!field(OemMapStartupGate.class, "ready").getBoolean(null), "false does not open gate");
            field(OemMapStartupGate.class, "pending").setBoolean(null, true);
            OemMapStartupGate.tick();
            quiet(peer);
            OemMapStartupGate.completeProbe(generation(), true);
            OemMapStartupGate.tick();
            expectReady(peer);
            for (int i = 0; i < 10; ++i) OemMapStartupGate.tick();
            quiet(peer);
            peer.close();
            peer = connect();
            OemMapStartupGate.tick();
            expectReady(peer);
            OemMapStartupGate.setActive(false);
            OemMapStartupGate.tick();
            quiet(peer);
        } finally {
            OemMapStartupGate.setActive(false);
            if (peer != null) peer.close();
            BUS.stop();
        }
        System.out.println("OEM map gate: real bus, false/stale probes, once per receiver and inactive withdrawal PASS");
    }
}
