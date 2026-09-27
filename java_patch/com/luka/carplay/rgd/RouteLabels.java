package com.luka.carplay.rgd;

import java.io.UnsupportedEncodingException;

/** One atomic distance/next-road snapshot for the custom maneuver panel. */
final class RouteLabels {
    static final int DISTANCE_BYTES = 12;
    static final int ROAD_BYTES = 32;

    private RouteLabels() {}

    static String distance(int value, int unit) {
        if (value <= 0 || unit < 0 || unit > 7) return "";
        String[] units = {"m", "km", "yd", "ft", "mi", "mi", "km", "mi"};
        // BAP units are tenths, except unit 5: tenths of a quarter mile.
        long hundredths = unit == 5 ? value * 5L / 2 : value * 10L;
        String number = Long.toString(hundredths / 100);
        int fraction = (int)(hundredths % 100);
        if (fraction != 0) {
            number += "." + (fraction < 10 ? "0" : "") + fraction;
            if (fraction % 10 == 0) number = number.substring(0, number.length() - 1);
        }
        String text = number + " " + units[unit];
        return text.length() <= DISTANCE_BYTES ? text : "";
    }

    static String clip(String text, int limit) {
        if (text == null || text.length() == 0) return "";
        text = VCUnicode.nfc(text);
        try {
            if (text.getBytes("UTF-8").length <= limit) return text;
            int[] ends = VCUnicode.boundaries(text);
            int end = 0;
            for (int i = 0; i < ends.length; i++) {
                if (text.substring(0, ends[i]).getBytes("UTF-8").length > limit - 3) break;
                end = ends[i];
            }
            return text.substring(0, end) + "\u2026";
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable: " + e);
        }
    }

    static byte[] packet(String distance, String road) {
        byte[] packet = new byte[48];
        packet[0] = RendererServer.CMD_ROUTE_LABELS;
        try {
            byte[] d = clip(distance, DISTANCE_BYTES).getBytes("UTF-8");
            byte[] r = clip(road, ROAD_BYTES).getBytes("UTF-8");
            packet[2] = (byte)d.length;
            packet[3] = (byte)r.length;
            System.arraycopy(d, 0, packet, 4, d.length);
            System.arraycopy(r, 0, packet, 4 + DISTANCE_BYTES, r.length);
            return packet;
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unavailable: " + e);
        }
    }
}
