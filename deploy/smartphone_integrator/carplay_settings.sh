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
        n=split("enabled mode layout distance road lanes progress text_size road_scroll background zoom zoom_speed touchpad touch_sensitivity recovery verbose info_default info_road info_return preset mascot",keys," ")
        for(i=1;i<=n;i++){known[keys[i]]=1;max[keys[i]]=1}
        max["mode"]=2;max["layout"]=3;max["touch_sensitivity"]=2;max["preset"]=3;max["mascot"]=2
        value["info_default"]=value["info_road"]=value["info_return"]=value["preset"]=value["mascot"]=0
      }
      /^#/ || /^$/ {next}
      {
        if(NF!=2 || $2 !~ /^[0-9]+$/ || seen[$1]++) {bad=1;next}
        if($1=="format"){if($2 !~ /^[123]$/)bad=1;format=$2+0;next}
        if(!known[$1] || $2<0 || $2>max[$1]){bad=1;next}
        value[$1]=$2+0
      }
      END {
        if(!format || !known[wanted])bad=1
        count=(format==1?16:(format==2?20:n))
        for(i=1;i<=n;i++) {
          if(i>count){if(seen[keys[i]])bad=1}
          else if(!seen[keys[i]])bad=1
        }
        if(bad)exit 1
        if(value["preset"]>0) {
          value["distance"]=value["lanes"]=1
          value["road"]=value["progress"]=(value["preset"]==1?0:1)
          value["text_size"]=(value["preset"]==3?1:0)
          value["road_scroll"]=0;value["background"]=(value["preset"]==1?1:0)
        }
        print value[wanted]
      }
    ' "$CP_SETTINGS_FILE" || { echo "SETTINGS=INVALID syntax_or_value" >&2; return 1; }
}
