package com.luka.carplay.settings;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public final class PreferencesTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void expectInvalid(String text) throws Exception {
        try {Preferences.parse(text);throw new AssertionError("accepted invalid preferences");}
        catch(IOException expected){}
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("carplay-prefs-");
        File file=root.resolve("settings/preferences").toFile();
        File legacy=root.resolve("cluster_ui.url").toFile();
        Files.write(legacy.toPath(),"maps:/car/instrumentcluster/map?maneuverLayout=rightaligned".getBytes("UTF-8"));
        Preferences preferences=new Preferences(file,legacy);
        check(preferences.snapshot().on(Setting.ENABLED),"missing preference keeps existing On behavior");
        check(preferences.snapshot().get(Setting.LAYOUT)==1,"legacy layout retained");
        for(int id=0;id<Setting.ALL.length;id++) {
            for(int value=0;value<Setting.ALL[id].choices.length;value++) {
                preferences.set(id,value);
                Preferences again=new Preferences(file,legacy);
                check(again.snapshot().get(id)==value,"persisted "+Setting.ALL[id].key);
            }
        }
        preferences.set(Setting.ENABLED,0);
        preferences.reset();
        check(!preferences.snapshot().on(Setting.ENABLED),"reset must not enable extra stream");
        check(preferences.snapshot().get(Setting.LAYOUT)==0,"reset display default");
        String valid=Preferences.read(file,8192);
        if(args.length>1)check(valid.equals(new String(Files.readAllBytes(Paths.get(args[1])),"UTF-8")),"shell/Java fixture format");
        expectInvalid(valid+"enabled=1\n");
        expectInvalid(valid.replace("mode=0","mode=3"));
        expectInvalid(valid.replace("format=1","format=2"));
        expectInvalid(valid.replace("road=1\n",""));
        expectInvalid(valid.replace("enabled=0","enabled=+1"));
        expectInvalid(valid+"command=reboot\n");
        Files.write(file.toPath(),"format=1\nenabled=1\n".getBytes("UTF-8"));
        preferences.refresh();
        check(!preferences.snapshot().on(Setting.ENABLED) && preferences.error().length()>0,"corrupt settings fail to stock mode");
        preferences.reset();
        check(preferences.error().length()==0 && !preferences.snapshot().on(Setting.ENABLED),"reset repairs without enabling");
        byte[] before=Files.readAllBytes(file.toPath());
        Files.createDirectory(file.toPath().resolveSibling("preferences.new"));
        try {preferences.set(Setting.ENABLED,1);throw new AssertionError("write should fail");}
        catch(IOException expected){}
        check(Arrays.equals(before,Files.readAllBytes(file.toPath())),"failed write preserves prior settings");
        for(String page:new String[]{"root","presentation","guidance","appearance","controls","diagnostics","recovery","status","confirm:reset","confirm:export_full","confirm:restart_video"}) {
            MenuModel.Row[] rows=MenuModel.rows(page,preferences.snapshot(),new String[]{"Build test"},"Ready");
            check(rows.length>1 && rows[0].target.equals("back"),"back navigation on "+page);
            for(MenuModel.Row row:rows)check(!row.target.contains("reboot") && !row.target.contains("restore"),"no maintenance action");
        }
        for(int id=0;id<Setting.ALL.length;id++) {
            MenuModel.Row[] rows=MenuModel.rows("choice:"+id,preferences.snapshot(),new String[0],"Ready");
            check(rows.length==Setting.ALL[id].choices.length+3,"all validated choices shown");
        }
        if(args.length>0)Files.write(Paths.get(args[0]),valid.getBytes("UTF-8"));
        try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}
        System.out.println("PreferencesTest: all choices, persistence, legacy migration, invalid/corrupt input, atomic failure and reset/master invariants PASS");
    }
}
