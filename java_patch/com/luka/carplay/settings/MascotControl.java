package com.luka.carplay.settings;

import java.io.*;

/** Expiring RAM-file control, written only by the settings worker. */
public final class MascotControl {
    static final String CONTROL="/ramdisk/carplay_mascot.control";
    static final String STATUS="/ramdisk/carplay_mascot.status";
    private MascotControl(){}
    public static void publish(int selected) throws IOException {
        if(selected<0 || selected>2)throw new IOException("Invalid map mascot");
        int pid=0;
        File owner=new File("/tmp/MMI-Cockpit-Carplay.mirror.pid");
        if(selected!=0 && owner.isFile()) {
            try {pid=Integer.parseInt(Preferences.read(owner,32).trim());}
            catch(NumberFormatException e){throw new IOException("Invalid mirror owner");}
            if(pid<=1)throw new IOException("Invalid mirror owner");
            if(!new File("/proc/"+pid).isDirectory()){selected=0;pid=0;}
        } else selected=0;
        File file=new File(CONTROL), temporary=new File(CONTROL+".new");
        if(!file.getParentFile().isDirectory())throw new IOException("Mascot RAM filesystem unavailable");
        byte[] bytes=("MASCOT2 "+selected+" "+pid+" "+(System.currentTimeMillis()+4000L)+"\n").getBytes("US-ASCII");
        FileOutputStream output=new FileOutputStream(temporary);
        try {output.write(bytes);output.flush();}finally{output.close();}
        if(!temporary.renameTo(file))throw new IOException("Cannot publish map mascot control on /ramdisk");
    }
    public static String status() throws IOException {
        File status=new File(STATUS);
        File owner=new File("/tmp/MMI-Cockpit-Carplay.mirror.pid");
        if(!status.isFile() || !owner.isFile())return "NOT_RUNNING";
        BufferedReader reader=new BufferedReader(new StringReader(Preferences.read(status,256)));
        String pid=null,state=null,expiry=null;
        for(String line;(line=reader.readLine())!=null;) {
            if(line.startsWith("pid=") && pid==null)pid=line.substring(4);
            else if(line.startsWith("state=") && state==null)state=line.substring(6);
            else if(line.startsWith("expires=") && expiry==null)expiry=line.substring(8);
            else throw new IOException("Invalid mascot status");
        }
        long expires;
        int process;
        try {expires=Long.parseLong(expiry);process=Integer.parseInt(pid);}
        catch(NumberFormatException e){throw new IOException("Incomplete mascot status");}
        long now=System.currentTimeMillis();
        if(process<=1 || expires<now || expires-now>5000
                || !pid.equals(Preferences.read(owner,32).trim()) || !new File("/proc/"+pid).isDirectory())
            return "NOT_RUNNING";
        if(state==null || state.length()==0 || state.length()>32)throw new IOException("Invalid mascot status");
        for(int i=0;i<state.length();i++) {
            char c=state.charAt(i);
            if(c!='_' && (c<'A' || c>'Z'))throw new IOException("Invalid mascot state");
        }
        return state;
    }
}
