package com.luka.carplay.cluster;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;

/** Real layout selection script plus the Java reader; no vehicle access. */
public final class AltScreenLayoutTest {
    private static final String BASE = "maps:/car/instrumentcluster/map";
    private static final String TOP = BASE + "?maneuverLayout=topaligned";

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void write(File file, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try { out.write(text.getBytes("UTF-8")); } finally { out.close(); }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "provide cluster_layout.sh path");
        File root = Files.createTempDirectory("altscreen-layout-").toFile();
        File hooks = new File(root, "mnt/app/root/hooks");
        check(hooks.mkdirs(), "create fake unit");
        File preference = new File(hooks, "cluster_ui.url");
        Field path = AltScreenCluster.class.getDeclaredField("uiUrlPath");
        path.setAccessible(true);
        Object previous = path.get(null);
        path.set(null, preference.getPath());
        try {
            check(TOP.equals(AltScreenCluster.readUiUrl()), "absent preference defaults to top");
            check(!preference.exists(), "default lookup must not create or overwrite a preference");
            String[] modes = {"right", "noeta", "default", "top"};
            String[] urls = {BASE + "?maneuverLayout=rightaligned", BASE + "?showETA=no", BASE, TOP};
            for (int i = 0; i < modes.length; i++) {
                ProcessBuilder builder = new ProcessBuilder("/bin/sh", args[0], modes[i]);
                Map<String,String> env = builder.environment();
                env.put("ALTSCREEN_CHAIN_TESTING", "1");
                env.put("ALTSCREEN_CHAIN_ROOT", root.getPath());
                builder.redirectErrorStream(true);
                Process process = builder.start();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                InputStream in = process.getInputStream();
                try {
                    byte[] buffer = new byte[1024];
                    for (int n; (n = in.read(buffer)) >= 0;) output.write(buffer, 0, n);
                } finally { in.close(); }
                check(process.waitFor() == 0, modes[i] + ": " + output.toString("UTF-8"));
                check(preference.isFile(), modes[i] + " must save an explicit preference");
                check(urls[i].equals(AltScreenCluster.readUiUrl()), modes[i] + " reader mismatch");
                check(urls[i].equals(AltScreenCluster.readUiUrl()), modes[i] + " must survive repeated reads");
            }
            write(preference, "https://example.invalid/not-a-layout");
            check(AltScreenCluster.readUiUrl() == null, "invalid preference must not become the default");
            write(preference, "");
            check(AltScreenCluster.readUiUrl() == null, "empty saved preference remains invalid");
            check(preference.delete(), "remove preference");
            check(TOP.equals(AltScreenCluster.readUiUrl()), "no preference restores automatic top layout");
        } finally {
            path.set(null, previous);
            try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            }
        }
        System.out.println("AltScreenLayoutTest: automatic top, explicit presets, original layout, invalid input PASS");
    }
}
