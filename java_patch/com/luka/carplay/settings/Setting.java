package com.luka.carplay.settings;

/** Validated, UI-facing settings. Values are indices, not arbitrary shell input. */
public final class Setting {
    public static final int ENABLED=0, MODE=1, LAYOUT=2, DISTANCE=3, ROAD=4, LANES=5,
        PROGRESS=6, TEXT_SIZE=7, ROAD_SCROLL=8, BACKGROUND=9, ZOOM=10, ZOOM_SPEED=11,
        TOUCHPAD=12, TOUCH_SENSITIVITY=13, RECOVERY=14, VERBOSE=15,
        INFO_DEFAULT=16, INFO_ROAD=17, INFO_RETURN=18, PRESET=19, MASCOT=20;
    public static final int VERSION_1_COUNT=16;
    public static final int VERSION_2_COUNT=20;
    private static final String[] SWITCH={"Off","On"};
    public static final Setting[] ALL={
        new Setting("enabled","Enabled",SWITCH,1,true),
        new Setting("mode","Cockpit presentation",new String[]{"CarPlay map + guidance","CarPlay map only","Audi map + guidance"},0,false),
        new Setting("layout","CarPlay map layout",new String[]{"Maneuver card on top","Maneuver card on right","No ETA","Original AltScreen"},0,true),
        new Setting("distance","Overlay distance",SWITCH,1,false),
        new Setting("road","Overlay next road / exit",SWITCH,1,false),
        new Setting("lanes","Overlay lane guidance",SWITCH,1,false),
        new Setting("progress","Arrow progress fill",SWITCH,1,false),
        new Setting("text_size","Overlay text size",new String[]{"Standard","Large"},0,false),
        new Setting("road_scroll","Road label overflow",new String[]{"Shorten","Scroll displayed label"},0,false),
        new Setting("background","Guidance background",new String[]{"Solid","Reduced"},0,false),
        new Setting("zoom","CarPlay wheel zoom",SWITCH,1,false),
        new Setting("zoom_speed","CarPlay zoom speed",new String[]{"Normal","Fast"},0,false),
        new Setting("touchpad","CarPlay touchpad navigation",SWITCH,1,false),
        new Setting("touch_sensitivity","Touchpad sensitivity",new String[]{"Low","Normal","High"},1,false),
        new Setting("recovery","Automatic mirror recovery",SWITCH,1,false),
        new Setting("verbose","Diagnostic logging",new String[]{"Normal","Verbose next session"},0,true),
        new Setting("info_default","Default information page",new String[]{"Road / exit","Trip summary"},0,false),
        new Setting("info_road","Information-bar road text",new String[]{"Next road / exit","Current road"},0,false),
        new Setting("info_return","Information-page selection",new String[]{"Return to default after 20 seconds","Keep until OK or route ends"},0,false),
        new Setting("preset","Overlay appearance preset",new String[]{"Custom","Minimal","Standard","Large text"},0,false),
        new Setting("mascot","Map mascot",MascotCatalog.CHOICES,0,false)
    };
    public final String key, label;
    public final String[] choices;
    public final int defaultValue;
    public final boolean reconnect;
    private Setting(String key,String label,String[] choices,int value,boolean reconnect) {
        this.key=key;this.label=label;this.choices=choices;this.defaultValue=value;this.reconnect=reconnect;
    }
    public boolean isSwitch(){return choices==SWITCH;}
    public static int index(String key) {
        for(int i=0;i<ALL.length;i++)if(ALL[i].key.equals(key))return i;
        return -1;
    }
    public static boolean isAppearance(int id){return id>=DISTANCE && id<=BACKGROUND;}
    public static int presetValue(int preset,int id,int custom) {
        if(preset==0 || !isAppearance(id))return custom;
        if(preset==1) {
            return id==DISTANCE || id==LANES || id==BACKGROUND?1:0;
        }
        if(id==TEXT_SIZE)return preset==3?1:0;
        return id==DISTANCE || id==ROAD || id==LANES || id==PROGRESS?1:0;
    }
}
