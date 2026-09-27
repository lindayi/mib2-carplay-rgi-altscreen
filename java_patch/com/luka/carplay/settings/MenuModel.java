package com.luka.carplay.settings;

import java.util.ArrayList;

/** Navigation and action model, independent of the native drawing implementation. */
public final class MenuModel {
    public static final class Row {
        public final String label,target;
        public final int setting,value;
        public final boolean heading,checkable,checked;
        Row(String label,String target,int setting,int value,boolean heading,boolean checkable,boolean checked) {
            this.label=label;this.target=target;this.setting=setting;this.value=value;
            this.heading=heading;this.checkable=checkable;this.checked=checked;
        }
    }
    private MenuModel(){}
    private static void item(ArrayList rows,String label,String target) {
        rows.add(new Row(label,target,-1,0,false,false,false));
    }
    private static void settings(ArrayList rows,int[] ids,Preferences.Snapshot prefs) {
        for(int i=0;i<ids.length;i++) {
            int id=ids[i];Setting spec=Setting.ALL[id];int value=prefs.get(id);
            String label=spec.label+(spec.isSwitch()?"":": "+spec.choices[value]);
            if(spec.reconnect)label+=" [reconnect]";
            rows.add(new Row(label,spec.isSwitch()?"set":"choice:"+id,id,
                spec.isSwitch()?1-value:value,false,spec.isSwitch(),value!=0));
        }
    }
    public static String parent(String page) {
        if(page.equals("root"))return "";
        if(page.startsWith("choice:")) {
            int id=Integer.parseInt(page.substring(7));
            if(id==Setting.MODE)return "root";
            if(id==Setting.PRESET)return "appearance";
            if(id>=Setting.INFO_DEFAULT)return "information";
            if(id<=Setting.LAYOUT)return "presentation";
            if(id<=Setting.PROGRESS)return "guidance";
            if(id<=Setting.BACKGROUND)return "appearance";
            if(id<=Setting.TOUCH_SENSITIVITY)return "controls";
            return id==Setting.RECOVERY?"recovery":"diagnostics";
        }
        if(page.equals("status") || page.equals("confirm:export_full"))return "diagnostics";
        if(page.equals("confirm:restart_video"))return "recovery";
        return "root";
    }
    public static Row[] rows(String page,Preferences.Snapshot prefs,String[] status,String message) {
        ArrayList rows=new ArrayList();
        item(rows,"Back","back");
        String title=page.startsWith("choice:")?Setting.ALL[Integer.parseInt(page.substring(7))].label:
            page.equals("root")?"Carplay Altscreen":page.equals("presentation")?"Cockpit presentation":
            page.equals("guidance")?"Guidance details":page.equals("appearance")?"Overlay appearance":
            page.equals("information")?"VC information bar":page.equals("controls")?"Controls":page.equals("diagnostics")?"Status & diagnostics":
            page.equals("recovery")?"Recovery":page.equals("status")?"System status":"Confirm action";
        rows.add(new Row(title,"",-1,0,true,false,false));
        if(page.equals("root")) {
            settings(rows,new int[]{Setting.ENABLED,Setting.MODE},prefs);
            item(rows,"Reapply cluster layout","action:reapply_layout");
            item(rows,"Phone map layout","presentation");
            item(rows,"VC information bar","information");
            item(rows,"Guidance details","guidance");
            item(rows,"Overlay appearance","appearance");
            item(rows,"Controls","controls");
            item(rows,"Status & diagnostics","diagnostics");
            item(rows,"Recovery","recovery");
            item(rows,"Reset display/control preferences","confirm:reset");
        } else if(page.equals("presentation"))settings(rows,new int[]{Setting.LAYOUT},prefs);
        else if(page.equals("information")) {
            settings(rows,new int[]{Setting.INFO_DEFAULT,Setting.INFO_ROAD,Setting.INFO_RETURN},prefs);
            rows.add(new Row("Arrival uses destination time zone when supplied","",-1,0,true,false,false));
        }
        else if(page.equals("guidance"))settings(rows,new int[]{Setting.DISTANCE,Setting.ROAD,Setting.LANES,Setting.PROGRESS},prefs);
        else if(page.equals("appearance")) {
            settings(rows,new int[]{Setting.PRESET,Setting.TEXT_SIZE,Setting.ROAD_SCROLL,Setting.BACKGROUND},prefs);
            rows.add(new Row("Custom is retained; editing a preset creates new Custom","",-1,0,true,false,false));
        }
        else if(page.equals("controls"))settings(rows,new int[]{Setting.ZOOM,Setting.ZOOM_SPEED,Setting.TOUCHPAD,Setting.TOUCH_SENSITIVITY},prefs);
        else if(page.equals("diagnostics")) {
            item(rows,"System status","status");
            item(rows,"Export summary to SD","action:export_summary");
            item(rows,"Export full diagnostics to SD","confirm:export_full");
            settings(rows,new int[]{Setting.VERBOSE},prefs);
        } else if(page.equals("recovery")) {
            settings(rows,new int[]{Setting.RECOVERY},prefs);
            item(rows,"Restart cockpit video only","confirm:restart_video");
        } else if(page.equals("status")) {
            for(int i=0;i<status.length;i++)rows.add(new Row(status[i],"",-1,0,true,false,false));
        } else if(page.startsWith("choice:")) {
            int id=Integer.parseInt(page.substring(7));Setting spec=Setting.ALL[id];
            for(int i=0;i<spec.choices.length;i++)rows.add(new Row(spec.choices[i],"choose",id,i,false,true,prefs.get(id)==i));
        } else if(page.startsWith("confirm:")) {
            String action=page.substring(8);
            String warning=action.equals("reset")?"Master switch and Audi settings will be preserved":
                action.equals("export_full")?"Raw logs may contain trip and device information":
                "Cockpit video may briefly blank. Main CarPlay is not restarted.";
            rows.add(new Row(warning,"",-1,0,true,false,false));
            item(rows,"Confirm","action:"+action);
        } else throw new IllegalArgumentException("Unknown menu page");
        rows.add(new Row(message,"",-1,0,true,false,false));
        return (Row[])rows.toArray(new Row[rows.size()]);
    }
}
