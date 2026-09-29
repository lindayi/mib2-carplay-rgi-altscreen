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
        check(Arrays.equals(Setting.ALL[Setting.MASCOT].choices,
            new String[]{"Off","Raccoon","Nian","Capybara","Lizard"}),"stable mascot IDs");
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
        expectInvalid(valid.replace("format=3","format=4"));
        expectInvalid(valid.replace("format=3","format=1"));
        expectInvalid(valid.replace("format=3","format=2"));
        expectInvalid(valid.replace("mascot=0\n",""));
        expectInvalid(valid.replace("mascot=0","mascot=5"));
        expectInvalid(valid.replace("info_return=0\n",""));
        expectInvalid(valid.replace("preset=0","preset=4"));
        expectInvalid(valid.replace("road=1\n",""));
        expectInvalid(valid.replace("enabled=0","enabled=+1"));
        expectInvalid(valid+"command=reboot\n");
        String version2=valid.substring(0,valid.indexOf("mascot=")).replace("format=3","format=2");
        if(args.length>1)check(version2.equals(new String(Files.readAllBytes(
            Paths.get(args[1]).resolveSibling("carplay-preferences-v2.txt")),"UTF-8")),"historical v2 fixture");
        Files.write(file.toPath(),version2.getBytes("UTF-8"));
        preferences.refresh();
        check(preferences.error().length()==0 && preferences.snapshot().get(Setting.MASCOT)==0,"v2 mascot defaults Off");
        check(Preferences.read(file,8192).equals(version2),"v2 read never rewrites");
        expectInvalid(version2+"mascot=0\n");
        String version1=valid.substring(0,valid.indexOf("info_default=")).replace("format=3","format=1");
        if(args.length>1)check(version1.equals(new String(Files.readAllBytes(
            Paths.get(args[1]).resolveSibling("carplay-preferences-v1.txt")),"UTF-8")),"historical v1 fixture");
        Files.write(file.toPath(),version1.getBytes("UTF-8"));
        preferences.refresh();
        check(preferences.error().length()==0,"complete v1 migrates");
        check(Preferences.read(file,8192).equals(version1),"reading migration never rewrites");
        for(int id=Setting.VERSION_1_COUNT;id<Setting.ALL.length;id++)
            check(preferences.snapshot().get(id)==0,"explicit v1 default: "+Setting.ALL[id].key);
        expectInvalid(version1.replace("road=1\n",""));
        expectInvalid(version1+"preset=0\n");
        preferences.set(Setting.INFO_DEFAULT,1);
        check(Preferences.read(file,8192).startsWith("format=3\n"),"next save upgrades schema");
        preferences.set(Setting.DISTANCE,0);
        preferences.set(Setting.ROAD_SCROLL,1);
        preferences.set(Setting.MASCOT,4);
        int[] custom=preferences.snapshot().copy();
        int[][] presetValues={{0,1,1,1,0,1,0},{1,0,1,0,0,0,1},{1,1,1,1,0,0,0},{1,1,1,1,1,0,0}};
        for(int preset=0;preset<4;preset++) {
            preferences.set(Setting.PRESET,preset);
            preferences.refresh();
            for(int id=Setting.DISTANCE;id<=Setting.BACKGROUND;id++) {
                check(preferences.snapshot().get(id)==presetValues[preset][id-Setting.DISTANCE],"effective preset");
                check(preferences.snapshot().copy()[id]==custom[id],"preset destroyed Custom");
            }
            check(preferences.snapshot().get(Setting.INFO_DEFAULT)==1 && !preferences.snapshot().on(Setting.ENABLED)
                && preferences.snapshot().get(Setting.MASCOT)==4,
                "preset changes unrelated settings");
        }
        preferences.set(Setting.PRESET,0);
        check(!preferences.snapshot().on(Setting.DISTANCE) && preferences.snapshot().on(Setting.ROAD_SCROLL),
            "Custom restores stored values");
        preferences.set(Setting.PRESET,1);
        preferences.set(Setting.ROAD,1);
        check(preferences.snapshot().get(Setting.PRESET)==0 && preferences.snapshot().on(Setting.ROAD)
            && preferences.snapshot().on(Setting.DISTANCE) && !preferences.snapshot().on(Setting.PROGRESS)
            && !preferences.snapshot().on(Setting.ROAD_SCROLL),"editing preset materializes new Custom");
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
        for(String page:new String[]{"root","presentation","information","guidance","appearance","controls","diagnostics","recovery","status","confirm:reset","confirm:export_full","confirm:restart_video"}) {
            MenuModel.Row[] rows=MenuModel.page(page,preferences.snapshot(),new String[]{"Build test"},"Ready").rows;
            check(rows.length>0 && rows[0].target.equals("back"),"back navigation on "+page);
            for(MenuModel.Row row:rows)check(!row.target.contains("reboot") && !row.target.contains("restore"),"no maintenance action");
        }
        for(int id=0;id<Setting.ALL.length;id++) {
            MenuModel.Row[] rows=MenuModel.page("choice:"+id,preferences.snapshot(),new String[0],"Ready").rows;
            check(rows.length==Setting.ALL[id].choices.length+1,"all validated choices shown");
        }
        check(MenuModel.parent("choice:"+Setting.MODE).equals("root"),"presentation control returns to root");
        check(MenuModel.parent("choice:"+Setting.INFO_DEFAULT).equals("information"),"information Back");
        check(MenuModel.parent("choice:"+Setting.PRESET).equals("appearance"),"preset Back");
        check(MenuModel.parent("choice:"+Setting.MASCOT).equals("appearance"),"mascot Back");
        boolean mode=false,reapply=false;
        for(MenuModel.Row row:MenuModel.page("root",preferences.snapshot(),new String[0],"Ready").rows) {
            mode|=row.target.equals("choice:"+Setting.MODE);
            reapply|=row.target.equals("action:reapply_layout");
        }
        check(mode && reapply,"direct presentation and layout controls");
        if(args.length>0)Files.write(Paths.get(args[0]),valid.getBytes("UTF-8"));
        try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new UncheckedIOException(e);}});}
        System.out.println("PreferencesTest: all choices, persistence, legacy migration, invalid/corrupt input, atomic failure and reset/master invariants PASS");
    }
}
