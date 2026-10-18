#!/bin/sh
# Read-only counterpart of Preferences.java. Never evaluate file contents.
CP_SETTINGS_FILE=${CP_SETTINGS_FILE:-/mnt/persist/var/app/carplay_altscreen/preferences}
cp_setting(){
    cp_key=$1; cp_default=$2
    if [ ! -e "$CP_SETTINGS_FILE" ]; then printf '%s\n' "$cp_default"; return 0; fi
    [ -f "$CP_SETTINGS_FILE" ] && [ -r "$CP_SETTINGS_FILE" ] || {
        echo "SETTINGS=INVALID unreadable_file" >&2; return 1; }
    set -- $(wc -c < "$CP_SETTINGS_FILE")
    [ "$1" -le 8192 ] || { echo "SETTINGS=INVALID oversized_file" >&2; return 1; }
    awk -F= -v wanted="$cp_key" '
      BEGIN {
        n=split("enabled mode layout distance road lanes progress text_size road_scroll background zoom zoom_speed touchpad touch_sensitivity recovery verbose info_default info_road info_return preset mascot now_playing_card trip_card speed_badge arrow_policy phone_eta",keys," ")
        for(i=1;i<=n;i++){known[keys[i]]=1;max[keys[i]]=1}
        max["mode"]=2;max["layout"]=4;max["touch_sensitivity"]=2;max["preset"]=3;max["mascot"]=4
        max["speed_badge"]=2
        value["info_default"]=value["info_road"]=value["info_return"]=value["preset"]=value["mascot"]=value["now_playing_card"]=value["trip_card"]=value["speed_badge"]=0
        value["arrow_policy"]=value["phone_eta"]=0
      }

      /^#/ || /^$/ {next}
      {
        if(NF!=2 || $2 !~ /^[0-9]+$/ || seen[$1]++) {bad=1;next}
        if($1=="format"){if($2 !~ /^[123456]$/)bad=1;format=$2+0;next}
        if(!known[$1] || $2<0 || $2>max[$1]){bad=1;next}
        value[$1]=$2+0
      }
      END {
        if(!format || (!known[wanted] && wanted!="phone_snapshot"))bad=1
        count=(format==1?16:(format==2?20:(format==3?21:(format==4?23:(format==5?24:n)))))
        for(i=1;i<=n;i++) {
          if(i>count){if(seen[keys[i]])bad=1}
          else if(!seen[keys[i]])bad=1
        }
        if(format<6) {
          if(value["speed_badge"]>1 || value["layout"]>3)bad=1
          value["phone_eta"]=(value["layout"]==2?0:1)
        }
        if(bad)exit 1
        if(value["preset"]>0) {
          value["distance"]=value["lanes"]=1
          value["road"]=value["progress"]=(value["preset"]==1?0:1)
          value["text_size"]=(value["preset"]==3?1:0)
          value["road_scroll"]=0;value["background"]=(value["preset"]==1?1:0)
        }
        if(wanted=="phone_snapshot")print value["layout"],(value["speed_badge"]==2?1:0),value["phone_eta"]
        else print value[wanted]
      }
    ' "$CP_SETTINGS_FILE" || { echo "SETTINGS=INVALID syntax_or_value" >&2; return 1; }
}

# The receiver must declare its geometry before its first AirPlay /info request.
cp_phone_layout(){
    if [ -e "$CP_SETTINGS_FILE" ]; then cp_setting layout 0; return $?; fi
    cp_legacy=${CP_LAYOUT_FILE:-${H:-/mnt/app/root/hooks}/cluster_ui.url}
    if [ ! -e "$cp_legacy" ]; then echo 0; return 0; fi
    [ -f "$cp_legacy" ] && [ -r "$cp_legacy" ] || {
        echo "SETTINGS=INVALID unreadable_legacy_layout" >&2; return 1; }
    set -- $(wc -c < "$cp_legacy")
    [ "$1" -gt 0 ] && [ "$1" -le 400 ] || {
        echo "SETTINGS=INVALID legacy_layout_size" >&2; return 1; }
    awk '
      {text=text $0 "\n";if(length(text)>401)bad=1}
      END {
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", text)
        base="maps:/car/instrumentcluster/map"
        if(bad)exit 1
        if(text==base)print 3
        else if(text==base "?maneuverLayout=topaligned")print 0
        else if(text==base "?maneuverLayout=rightaligned")print 1
        else if(text==base "?showETA=no")print 2
        else exit 1
      }' "$cp_legacy" || { echo "SETTINGS=INVALID unsupported_legacy_layout" >&2; return 1; }
}

cp_phone_selection(){
    if [ -e "$CP_SETTINGS_FILE" ]; then cp_setting phone_snapshot '0 0 0'; return $?; fi
    cp_layout=$(cp_phone_layout) || return 1
    cp_eta=1
    [ -e "${CP_LAYOUT_FILE:-${H:-/mnt/app/root/hooks}/cluster_ui.url}" ] || cp_eta=0
    [ "$cp_layout" != 2 ] || cp_eta=0
    printf '%s 0 %s\n' "$cp_layout" "$cp_eta"
}
