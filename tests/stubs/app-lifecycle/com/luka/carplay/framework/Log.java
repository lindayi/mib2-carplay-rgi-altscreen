package com.luka.carplay.framework;
public final class Log {
    public static final int E = 0, W = 1, I = 2, D = 3;
    public static void setLevel(int level) { }
    public static void d(String tag, String message) { }
    public static void i(String tag, String message) { }
    public static void w(String tag, String message) { }
    public static void e(String tag, String message) { }
    public static void e(String tag, String message, Throwable error) { }
    public static void w(String tag, String message, Throwable error) { }
    public static void refreshLevel() { }
}
