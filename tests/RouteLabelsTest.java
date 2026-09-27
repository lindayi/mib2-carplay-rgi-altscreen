package com.luka.carplay.rgd;

import java.util.Arrays;

public final class RouteLabelsTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        check(RouteLabels.distance(1500,0).equals("150 m"), "meters");
        check(RouteLabels.distance(12,1).equals("1.2 km"), "kilometers");
        check(RouteLabels.distance(5000,3).equals("500 ft"), "feet");
        check(RouteLabels.distance(2000,2).equals("200 yd"), "yards");
        check(RouteLabels.distance(2,4).equals("0.2 mi"), "decimal miles");
        check(RouteLabels.distance(10,5).equals("0.25 mi"), "quarter miles");
        check(RouteLabels.distance(30,5).equals("0.75 mi"), "three quarter miles");
        check(RouteLabels.distance(-1,0).equals("") && RouteLabels.distance(0,0).equals(""), "invalid distance");
        check(RouteLabels.distance(10,255).equals(""), "unknown unit");
        check(RouteLabels.clip("Cafe\u0301 Street",32).equals("Caf\u00e9 Street"), "NFC");
        String longName = "ABCDEFGHIJKLMNOPQRSTUVWXYZ \ud83d\udc68\u200d\ud83d\udc69\u200d\ud83d\udc67 Street";
        String clipped = RouteLabels.clip(longName,32);
        check(clipped.endsWith("\u2026") && clipped.getBytes("UTF-8").length <= 32, "bounded ellipsis");
        check(clipped.indexOf('\ud83d') < 0, "no partial grapheme");
        byte[] wire = RouteLabels.packet("500 ft","Main Street");
        check(wire.length == 48 && wire[0] == 15 && wire[2] == 6 && wire[3] == 11, "wire header");
        check(new String(wire,4,6,"UTF-8").equals("500 ft"), "distance field");
        check(new String(wire,16,11,"UTF-8").equals("Main Street"), "road field");
        check(Arrays.equals(RouteLabels.packet("",""), new byte[]{
            15,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,
            0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0}), "clear snapshot");
        RouteGuidance.State s = new RouteGuidance.State();
        s.currentRoad="Current Road";
        check(BAPBridge.maneuverRoad(s,0).equals(""), "never substitute current road");
        s.mAfterRoad[0]="region:  Main  Street ";
        s.mExitInfo[0]=" Exit 12 ";
        check(BAPBridge.maneuverRoad(s,0).equals("Main Street"), "next road normalization");
        check(BAPBridge.maneuverSign(s,0).equals("Exit 12"), "exit text");
        System.out.println("RouteLabelsTest: stock unit encoding, UTF-8/graphemes, packet layout, next-road semantics PASS");
    }
}
