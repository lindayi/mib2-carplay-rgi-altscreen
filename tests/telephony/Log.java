package com.luka.carplay.framework;

/** The regression must not write vehicle logs or probe vehicle paths. */
public final class Log {
    public static void w(String tag, String message) { }
    public static void i(String tag, String message) { }
    public static void e(String tag, String message) { }
    public static void d(String tag, String message) { }
    public static void refreshLevel() { }
}
