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
            for(int i=0;i<rows.length;i++)if(rows[i].target.equals("choose") && rows[i].checked)return i;
            return 0;
        }
    }
    private MenuModel(){}
    private static void item(ArrayList rows,String label,String target) {
        rows.add(new Row(label,target,-1,0,false,false,actionHelp(target)));
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
        if(id==Setting.ENABLED)return "Off releases cockpit features now; the extra stream stops on the next CarPlay session.";
        if(id==Setting.MODE)return "Applies live where possible. Switching from an Audi-map receiver session needs reconnect.";
        if(id==Setting.LAYOUT)return "Request the phone's cluster-map layout. Reconnect CarPlay to apply; no MMI reboot.";
        if(id==Setting.PRESET)return "Custom is retained. Editing a preset creates a new Custom configuration.";
        if(id==Setting.MASCOT)return "Animation on the CarPlay cluster map only. The VC menu previews it; select to apply live.";
        if(id==Setting.NOW_PLAYING_CARD)return "Show phone media on a fixed translucent left map card. Applies live; Audi dials may cover it.";
        if(id==Setting.TRIP_CARD)return "Show ETA, remaining time/distance and estimated progress since this connection first observed the route. Rerouting can move the bar backward.";
        if(id==Setting.DISTANCE)return "Show distance to the next maneuver in the small guidance overlay.";
        if(id==Setting.ROAD)return "Show the next road or exit in the guidance overlay, not the current road.";
        if(id==Setting.LANES)return "Show the phone's available lane guidance beside the maneuver.";
        if(id==Setting.PROGRESS)return "Show progress toward the next maneuver with the arrow's fill.";
        if(id==Setting.TEXT_SIZE)return "Enlarge distance and road text in the small guidance overlay.";
        if(id==Setting.ROAD_SCROLL)return "Shorten or scroll the bounded road label; scrolling cannot recover omitted text.";
        if(id==Setting.BACKGROUND)return "Choose a solid or reduced backing behind the guidance overlay.";
        if(id==Setting.ZOOM)return "Let the steering-wheel roller zoom the CarPlay cluster map.";
        if(id==Setting.ZOOM_SPEED)return "Choose how quickly roller steps change the CarPlay map scale.";
        if(id==Setting.TOUCHPAD)return "Enable touchpad navigation in CarPlay; Audi's normal controls are unchanged.";
        if(id==Setting.TOUCH_SENSITIVITY)return "Adjust how strongly touchpad movement affects CarPlay navigation.";
        if(id==Setting.RECOVERY)return "Allow bounded recovery of cockpit video only; normal startup remains enabled.";
        if(id==Setting.VERBOSE)return "Record extra diagnostic detail on the next CarPlay session. Reconnect to apply.";
        if(id==Setting.INFO_DEFAULT)return "Choose the VC lower information bar's initial page for each route or session.";
        if(id==Setting.INFO_ROAD)return "Choose next-road or current-road text in the VC lower bar, not the small overlay.";
        if(id==Setting.INFO_RETURN)return "Return the VC lower bar to its default page, or keep your choice until the route ends.";
        throw new IllegalArgumentException("Unknown setting help");
    }
    private static String actionHelp(String target) {
        if(target.equals("back"))return "Cancel without applying this action. The physical Back button also returns.";
        if(target.equals("action:reapply_layout"))return "Request this connection's map layout again without reconnecting. Recentering is not guaranteed.";
        if(target.equals("presentation"))return "Choose the layout requested from the phone for the cluster map.";
        if(target.equals("cards"))return "Optional fixed left-side map cards. Placement behind Audi dials needs a parked check.";
        if(target.equals("information"))return "Configure Road and Trip text in the VC lower information bar.";
        if(target.equals("guidance"))return "Choose the details shown around the small maneuver arrow.";
        if(target.equals("appearance"))return "Adjust overlay text and background, presets, and the optional map mascot.";
        if(target.equals("controls"))return "Adjust CarPlay roller zoom and touchpad behavior.";
        if(target.equals("diagnostics"))return "View runtime status and export diagnostics to the SD card.";
        if(target.equals("recovery"))return "Configure automatic recovery or request a cockpit-video-only restart.";
        if(target.equals("status"))return "View the build, receiver session, preferences and renderer status.";
        if(target.equals("result"))return "Read the last action result, including retained failure details.";
        if(target.equals("action:export_summary"))return "Write a selected status summary to SD without restarting or restoring.";
        if(target.equals("confirm:export_full") || target.equals("action:export_full"))
            return "Export private raw logs to SD. They may include trip or device information; nothing is uploaded.";
        if(target.equals("confirm:restart_video") || target.equals("action:restart_video"))
            return "Restart cockpit video only. It may briefly blank; main CarPlay is not restarted.";
        if(target.equals("confirm:reset") || target.equals("action:reset"))
            return "Reset project display and control preferences, preserving the master switch, Audi settings and pairing.";
        if(target.equals("text:previous"))return "Read the preceding page of information.";
        if(target.equals("text:next"))return "Read the next page of information.";
        throw new IllegalArgumentException("Unknown action help: "+target);
    }
    public static String parent(String page) {
        if(page.equals("root"))return "";
        if(page.startsWith("choice:")) {
            int id=Integer.parseInt(page.substring(7));
            if(id==Setting.MODE)return "root";
            if(id==Setting.NOW_PLAYING_CARD || id==Setting.TRIP_CARD)return "cards";
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
        if(page.startsWith("confirm:"))item(rows,"Cancel","back");
        String title=page.startsWith("choice:")?Setting.ALL[Integer.parseInt(page.substring(7))].label:
            page.equals("root")?"Carplay Altscreen":page.equals("presentation")?"Cockpit presentation":
            page.equals("guidance")?"Guidance details":page.equals("appearance")?"Overlay appearance":
            page.equals("cards")?"Map cards":page.equals("information")?"VC information bar":page.equals("controls")?"Controls":page.equals("diagnostics")?"Status & diagnostics":
            page.equals("recovery")?"Recovery":page.equals("status")?"System status":page.equals("result")?"Last result":"Confirm action";
        String information="";
        boolean document=false,paginated=false;
        if(page.equals("root")) {
            settings(rows,new int[]{Setting.ENABLED,Setting.MODE},prefs);
            item(rows,"Reapply cluster layout","action:reapply_layout");
            item(rows,"Phone map layout","presentation");
            item(rows,"Map cards","cards");
            item(rows,"VC information bar","information");
            item(rows,"Guidance details","guidance");
            item(rows,"Overlay appearance","appearance");
            item(rows,"Controls","controls");
            item(rows,"Status & diagnostics","diagnostics");
            item(rows,"Recovery","recovery");
            item(rows,"Reset display/control preferences","confirm:reset");
        } else if(page.equals("presentation"))settings(rows,new int[]{Setting.LAYOUT},prefs);
        else if(page.equals("cards"))settings(rows,new int[]{Setting.NOW_PLAYING_CARD,Setting.TRIP_CARD},prefs);
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
