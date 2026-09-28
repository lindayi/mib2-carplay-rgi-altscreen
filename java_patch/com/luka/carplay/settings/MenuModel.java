package com.luka.carplay.settings;

import java.util.ArrayList;

/** Navigation and action model, independent of the native drawing implementation. */
public final class MenuModel {
    public static final class Row {
        public final String label,target,help;
        public final int setting,value;
        public final boolean checkable,checked;
        Row(String label,String target,int setting,int value,boolean checkable,boolean checked,String help) {
            if(target.length()==0)throw new IllegalArgumentException("Menu controls require an action");
            this.label=label;this.target=target;this.setting=setting;this.value=value;
            this.checkable=checkable;this.checked=checked;this.help=help;
        }
        public String focusKey(){return target+":"+setting+(target.equals("choose")?":"+value:"");}
    }
    public static final class Page {
        public final String title,information;
        public final Row[] rows;
        public final boolean document,paginated;
        Page(String title,String information,ArrayList rows,boolean document,boolean paginated) {
            this.title=title;this.information=information;
            this.rows=(Row[])rows.toArray(new Row[rows.size()]);
            this.document=document;this.paginated=paginated;
        }
        public int focusIndex(String saved) {
            for(int i=0;i<rows.length;i++)if(rows[i].focusKey().equals(saved))return i;
            if(document)return 0;
            for(int i=1;i<rows.length;i++)if(rows[i].target.equals("choose") && rows[i].checked)return i;
            return rows.length>1?1:0;
        }
    }
    private MenuModel(){}
    private static void item(ArrayList rows,String label,String target) {
        rows.add(new Row(label,target,-1,0,false,false,""));
    }
    private static void settings(ArrayList rows,int[] ids,Preferences.Snapshot prefs) {
        for(int i=0;i<ids.length;i++) {
            int id=ids[i];Setting spec=Setting.ALL[id];int value=prefs.get(id);
            String label=spec.label+(spec.isSwitch()?"":": "+spec.choices[value]);
            rows.add(new Row(label,spec.isSwitch()?"set":"choice:"+id,id,
                spec.isSwitch()?1-value:value,spec.isSwitch(),value!=0,help(id)));
        }
    }
    private static String help(int id) {
        if(Setting.ALL[id].reconnect)return "Saved changes apply after you reconnect CarPlay. No MMI reboot.";
        if(id==Setting.ENABLED)return "Off releases cockpit features now; the extra stream stops on the next CarPlay session.";
        if(id==Setting.MODE)return "Applies live where possible. Switching from an Audi-map receiver session needs reconnect.";
        if(id==Setting.PRESET)return "Custom is retained. Editing a preset creates a new Custom configuration.";
        if(id==Setting.MASCOT)return "Animation on the CarPlay cluster map only. Off removes it live.";
        if(id>=Setting.INFO_DEFAULT && id<=Setting.INFO_RETURN)return "Uses the existing VC information bar. Arrival uses the destination time zone when supplied.";
        return "Applies live. No MMI reboot.";
    }
    public static String parent(String page) {
        if(page.equals("root"))return "";
        if(page.startsWith("choice:")) {
            int id=Integer.parseInt(page.substring(7));
            if(id==Setting.MODE)return "root";
            if(id==Setting.PRESET || id==Setting.MASCOT)return "appearance";
            if(id>=Setting.INFO_DEFAULT)return "information";
            if(id<=Setting.LAYOUT)return "presentation";
            if(id<=Setting.PROGRESS)return "guidance";
            if(id<=Setting.BACKGROUND)return "appearance";
            if(id<=Setting.TOUCH_SENSITIVITY)return "controls";
            return id==Setting.RECOVERY?"recovery":"diagnostics";
        }
        if(page.equals("status") || page.equals("result") || page.equals("confirm:export_full"))return "diagnostics";
        if(page.equals("confirm:restart_video"))return "recovery";
        return "root";
    }
    public static Page page(String page,Preferences.Snapshot prefs,String[] status,String message) {
        ArrayList rows=new ArrayList();
        item(rows,page.startsWith("confirm:")?"Cancel":"Back","back");
        String title=page.startsWith("choice:")?Setting.ALL[Integer.parseInt(page.substring(7))].label:
            page.equals("root")?"Carplay Altscreen":page.equals("presentation")?"Cockpit presentation":
            page.equals("guidance")?"Guidance details":page.equals("appearance")?"Overlay appearance":
            page.equals("information")?"VC information bar":page.equals("controls")?"Controls":page.equals("diagnostics")?"Status & diagnostics":
            page.equals("recovery")?"Recovery":page.equals("status")?"System status":page.equals("result")?"Last result":"Confirm action";
        String information="";
        boolean document=false,paginated=false;
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
        }
        else if(page.equals("guidance"))settings(rows,new int[]{Setting.DISTANCE,Setting.ROAD,Setting.LANES,Setting.PROGRESS},prefs);
        else if(page.equals("appearance")) {
            settings(rows,new int[]{Setting.PRESET,Setting.TEXT_SIZE,Setting.ROAD_SCROLL,Setting.BACKGROUND,Setting.MASCOT},prefs);
        }
        else if(page.equals("controls"))settings(rows,new int[]{Setting.ZOOM,Setting.ZOOM_SPEED,Setting.TOUCHPAD,Setting.TOUCH_SENSITIVITY},prefs);
        else if(page.equals("diagnostics")) {
            item(rows,"System status","status");
            item(rows,"Last result","result");
            item(rows,"Export summary to SD","action:export_summary");
            item(rows,"Export full diagnostics to SD","confirm:export_full");
            settings(rows,new int[]{Setting.VERBOSE},prefs);
        } else if(page.equals("recovery")) {
            settings(rows,new int[]{Setting.RECOVERY},prefs);
            item(rows,"Restart cockpit video only","confirm:restart_video");
        } else if(page.equals("status") || page.equals("result")) {
            document=true;paginated=true;
            StringBuffer text=new StringBuffer();
            if(page.equals("result"))text.append(message);
            else for(int i=0;i<status.length;i++) {
                if(i!=0)text.append('\n');
                text.append(status[i]);
            }
            information=text.length()==0?"No status information available":text.toString();
            item(rows,"Previous page","text:previous");
            item(rows,"Next page","text:next");
        } else if(page.startsWith("choice:")) {
            int id=Integer.parseInt(page.substring(7));Setting spec=Setting.ALL[id];
            for(int i=0;i<spec.choices.length;i++)rows.add(new Row(spec.choices[i],"choose",id,i,true,prefs.get(id)==i,help(id)));
        } else if(page.startsWith("confirm:")) {
            String action=page.substring(8);
            if(!action.equals("reset") && !action.equals("export_full") && !action.equals("restart_video"))
                throw new IllegalArgumentException("Unknown confirmation");
            document=true;
            information=action.equals("reset")?"Reset display and control preferences? Master switch, Audi settings and pairing will be preserved.":
                action.equals("export_full")?"Raw logs may contain trip and device information":
                "Cockpit video may briefly blank. Main CarPlay is not restarted.";
            item(rows,action.equals("reset")?"Reset preferences":action.equals("export_full")?
                "Export full diagnostics":"Restart cockpit video","action:"+action);
        } else throw new IllegalArgumentException("Unknown menu page");
        return new Page(title,information,rows,document,paginated);
    }
}
