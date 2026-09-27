package com.luka.carplay.settings;

import com.luka.carplay.framework.Log;
import java.io.*;
import java.util.ArrayList;

/** Small atomic file shared with the next-session launcher and GEM layout picker. */
public final class Preferences {
    public static final String PATH="/mnt/persist/var/app/carplay_altscreen/preferences";
    private static Preferences instance;
    private final File file;
    private final File legacyLayout;
    private final ArrayList listeners=new ArrayList();
    private volatile Snapshot snapshot;
    private volatile String error="";
    private long revision;

    public interface Listener { void preferencesChanged(); }
    public static final class Snapshot {
        private final int[] values;
        public final long revision;
        Snapshot(int[] values,long revision){this.values=values;this.revision=revision;}
        public int get(int id){return Setting.presetValue(values[Setting.PRESET],id,values[id]);}
        public boolean on(int id){return get(id)!=0;}
        int[] copy(){int[] next=new int[values.length];System.arraycopy(values,0,next,0,next.length);return next;}
    }
    public static synchronized Preferences get() {
        if(instance==null)instance=new Preferences(new File(PATH),new File("/mnt/app/root/hooks/cluster_ui.url"),false);
        return instance;
    }
    Preferences(File file,File legacyLayout) {
        this(file,legacyLayout,true);
    }
    private Preferences(File file,File legacyLayout,boolean loadNow) {
        this.file=file;this.legacyLayout=legacyLayout;
        snapshot=new Snapshot(defaults(),0);
        if(loadNow)refresh();
    }
    public Snapshot snapshot(){return snapshot;}
    public String error(){return error;}
    public boolean exists(){return file.isFile();}
    public synchronized void addListener(Listener listener){if(!listeners.contains(listener))listeners.add(listener);}
    public synchronized void removeListener(Listener listener){listeners.remove(listener);}
    private static int[] defaults(){
        int[] values=new int[Setting.ALL.length];
        for(int i=0;i<values.length;i++)values[i]=Setting.ALL[i].defaultValue;
        return values;
    }
    static String read(File source,int limit) throws IOException {
        FileInputStream in=new FileInputStream(source);
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[512];
            for(int n;(n=in.read(buffer))!=-1;) {
                if(out.size()+n>limit)throw new IOException("Settings file exceeds limit");
                out.write(buffer,0,n);
            }
            return new String(out.toByteArray(),"UTF-8");
        } finally {in.close();}
    }
    static int[] parse(String text) throws IOException {
        int[] values=defaults();
        boolean[] seen=new boolean[values.length];
        int format=0;
        BufferedReader reader=new BufferedReader(new StringReader(text));
        for(String line;(line=reader.readLine())!=null;) {
            if(line.length()==0 || line.charAt(0)=='#')continue;
            int equals=line.indexOf('=');
            if(equals<=0 || equals!=line.lastIndexOf('='))throw new IOException("Invalid setting line");
            String key=line.substring(0,equals), raw=line.substring(equals+1);
            if(key.equals("format")) {
                if(format!=0 || !raw.equals("1") && !raw.equals("2") && !raw.equals("3"))
                    throw new IOException("Unsupported settings format");
                format=Integer.parseInt(raw);continue;
            }
            int id=Setting.index(key);
            if(id<0 || seen[id])throw new IOException("Unknown or duplicate setting: "+key);
            for(int j=0;j<raw.length();j++)if(raw.charAt(j)<'0' || raw.charAt(j)>'9')
                throw new IOException("Invalid value for "+key);
            int value;
            try {value=Integer.parseInt(raw);} catch(NumberFormatException e){throw new IOException("Invalid value for "+key);}
            if(value<0 || value>=Setting.ALL[id].choices.length)throw new IOException("Out-of-range setting: "+key);
            values[id]=value;seen[id]=true;
        }
        if(format==0)throw new IOException("Missing settings format");
        int count=format==1?Setting.VERSION_1_COUNT:format==2?Setting.VERSION_2_COUNT:values.length;
        for(int i=0;i<seen.length;i++) {
            if(i>=count) {
                if(seen[i])throw new IOException("Setting not supported by declared version");
            } else if(!seen[i])throw new IOException("Missing setting: "+Setting.ALL[i].key);
        }
        return values;
    }
    private int[] load() throws IOException {
        if(file.exists())return parse(read(file,8192));
        int[] values=defaults();
        if(legacyLayout.isFile()) {
            String url=read(legacyLayout,1024).trim();
            if(url.equals("maps:/car/instrumentcluster/map"))values[Setting.LAYOUT]=3;
            else if(url.equals("maps:/car/instrumentcluster/map?maneuverLayout=rightaligned"))values[Setting.LAYOUT]=1;
            else if(url.equals("maps:/car/instrumentcluster/map?showETA=no"))values[Setting.LAYOUT]=2;
            else if(!url.equals("maps:/car/instrumentcluster/map?maneuverLayout=topaligned"))
                throw new IOException("Unrecognized saved cluster layout");
        }
        return values;
    }
    static String encode(int[] values) {
        StringBuffer out=new StringBuffer("format=3\n");
        for(int i=0;i<values.length;i++)out.append(Setting.ALL[i].key).append('=').append(values[i]).append('\n');
        return out.toString();
    }
    private void publish(int[] values,String problem) {
        boolean changed=!problem.equals(error);
        for(int i=0;i<values.length;i++)if(values[i]!=snapshot.values[i])changed=true;
        error=problem;
        if(!changed)return;
        snapshot=new Snapshot(values,++revision);
        Object[] copy=listeners.toArray();
        for(int i=0;i<copy.length;i++) {
            try {((Listener)copy[i]).preferencesChanged();}
            catch(RuntimeException e){Log.w("Settings","listener failed: "+e);}
        }
    }
    public synchronized void refresh() {
        try {publish(load(),"");}
        catch(IOException e) {
            String problem=e.getMessage();
            if(!problem.equals(error))Log.w("Settings","disabled: "+problem);
            int[] safe=defaults();safe[Setting.ENABLED]=0;
            publish(safe,problem);
        }
        catch(SecurityException e) {
            if(!"Settings access denied".equals(error))Log.e("Settings","Settings access denied",e);
            int[] safe=defaults();safe[Setting.ENABLED]=0;
            publish(safe,"Settings access denied");
        }
    }
    public synchronized void set(int id,int value) throws IOException {
        if(id<0 || id>=Setting.ALL.length || value<0 || value>=Setting.ALL[id].choices.length)
            throw new IOException("Invalid preference");
        int[] values=load();
        if(Setting.isAppearance(id) && values[Setting.PRESET]!=0) {
            int preset=values[Setting.PRESET];
            for(int i=0;i<values.length;i++)values[i]=Setting.presetValue(preset,i,values[i]);
            values[Setting.PRESET]=0;
        }
        values[id]=value;
        save(values);
    }
    public synchronized void reset() throws IOException {
        int[] values=defaults();
        values[Setting.ENABLED]=snapshot.get(Setting.ENABLED);
        save(values);
    }
    private void save(int[] values) throws IOException {
        File parent=file.getParentFile();
        if(!parent.isDirectory() && !parent.mkdirs())throw new IOException("Cannot create settings directory");
        File temporary=new File(parent,file.getName()+".new");
        boolean created=false;
        try {
            FileOutputStream out=new FileOutputStream(temporary);
            created=true;
            try {out.write(encode(values).getBytes("UTF-8"));out.flush();out.getFD().sync();}
            finally {out.close();}
            if(!temporary.renameTo(file))throw new IOException("Cannot publish settings atomically");
            publish(values,"");
        } finally {
            if(created && temporary.exists() && !temporary.delete())Log.w("Settings","cannot remove incomplete settings file");
        }
    }
}
