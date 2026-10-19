package com.luka.carplay.settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;

/** Exec-time receiver facts, separate from preferences saved during a connection. */
public final class ReceiverSession {
    public final int pid,enabled,video,verbose,configError;
    public final int phoneLayout,phoneSign,phoneEta;

    private ReceiverSession(int[] v) {
        pid=v[0];enabled=v[1];video=v[2];verbose=v[3];configError=v[4];
        phoneLayout=v[5];phoneSign=v[6];phoneEta=v[7];
    }
    public static ReceiverSession read(File file) throws IOException {
        return parse(Preferences.read(file,1024));
    }
    public static ReceiverSession parse(String text) throws IOException {
        String[] keys={"pid","enabled","video","verbose","config_error","phone_layout","phone_sign","phone_eta"};
        HashMap fields=new HashMap();
        BufferedReader reader=new BufferedReader(new StringReader(text));
        for(String line;(line=reader.readLine())!=null;) {
            int at=line.indexOf('=');
            if(at<=0 || at!=line.lastIndexOf('='))throw new IOException("Invalid session status");
            String key=line.substring(0,at);
            boolean known=false;
            for(int i=0;i<keys.length;i++)if(keys[i].equals(key))known=true;
            if(!known || fields.containsKey(key))throw new IOException("Unknown or duplicate session status field");
            fields.put(key,line.substring(at+1));
        }
        int[] v=new int[8];
        for(int i=0;i<v.length;i++) {
            String value=(String)fields.get(keys[i]);
            if(value==null) {
                if(i<5 || fields.size()!=5)throw new IOException("Incomplete session status");
                v[i]=-1;
            } else try {v[i]=Integer.parseInt(value);}
            catch(NumberFormatException e){throw new IOException("Invalid session status");}
        }
        if(v[0]<=1)throw new IOException("Invalid receiver PID");
        for(int i=1;i<5;i++)if(v[i]<0 || v[i]>1)throw new IOException("Invalid session flags");
        if(fields.size()==8) {
            if(v[2]==1) {
                if(v[1]!=1 || v[4]!=0 || v[5]<0 || v[5]>4 || v[6]<0 || v[6]>1 || v[7]<0 || v[7]>1)
                    throw new IOException("Invalid phone session selection");
            } else if(v[5]!=-1 || v[6]!=-1 || v[7]!=-1)throw new IOException("Inactive phone session selection");
        }
        return new ReceiverSession(v);
    }
    public boolean[] flags() throws IOException {
        File owner=new File("/ramdisk/carplay_supervisor.owner");
        boolean known=new File("/proc/"+pid).isDirectory() && owner.isFile()
            && Preferences.read(owner,32).trim().equals(Integer.toString(pid));
        return new boolean[]{known,known && enabled==1 && configError==0,
            known && video==1 && configError==0,known && verbose==1};
    }
}
