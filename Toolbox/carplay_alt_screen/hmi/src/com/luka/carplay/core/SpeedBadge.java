package com.luka.carplay.core;

import org.dsi.ifc.carvehiclestates.DynamicVehicleInfoHighFrequent;
import org.dsi.ifc.cardriverassistance.*;
import org.dsi.ifc.global.CarBCSpeed;
import org.dsi.ifc.trafficregulation.*;

/** Copied scalar state, owned by the passive source run's lock. No callback I/O. */
public final class SpeedBadge {
    public static final int MAX_VALUE=399;
    public static final int MOVING_LEASE_MS=2000;
    public static final int STOPPED_LEASE_MS=15000;
    private static volatile boolean wideMap;
    private float speed=-1;
    private int speedUnit=-1,mapLimit=-1,mapUnit=-1;
    private long speedAt;
    private boolean cameraOff,cameraBlind;
    private final int[] cameraLimit={-1,-1,-1,-1,-1},cameraUnit={-1,-1,-1,-1,-1};

    public static final class Snapshot {
        public boolean enabled,wide,over,conflict;
        public int speed=-1,limit=-1,unit=-1,source;
        public int connection=-1;
        public long expires;
    }
    public static void presentation(boolean largeMap){wideMap=largeMap;}
    void reset(int source) {
        if(source==0){speed=-1;speedUnit=-1;speedAt=0;}
        if(source==1){mapLimit=mapUnit=-1;}
        if(source==2) {
            clearCamera();cameraOff=cameraBlind=false;
        }
    }
    private void clearCamera(){for(int i=0;i<cameraLimit.length;i++)cameraLimit[i]=cameraUnit[i]=-1;}
    private static boolean unit(int value){return value==0 || value==1;}
    private static boolean limit(int value){return value>0 && value<=MAX_VALUE;}
    void update(int topic,Object value,int status,long now) {
        if(topic==0) {
            reset(0);
            CarBCSpeed s=status==1 && value!=null?((DynamicVehicleInfoHighFrequent)value).vehicleSpeed:null;
            if(s!=null && s.speedValueState==1 && unit(s.speedUnit) &&
                    !Float.isNaN(s.speedValue) && !Float.isInfinite(s.speedValue) &&
                    s.speedValue>=0 && s.speedValue<=MAX_VALUE) {
                speed=s.speedValue;speedUnit=s.speedUnit;speedAt=now;
            }
        } else if(topic==2) {
            reset(1);
            SpeedLimitInfo s=status==1 && value!=null?((TrafficSignInformation)value).highestPrioritySpeedLimit:null;
            if(s!=null && s.speedLimitType==DSITrafficRegulation.SPEEDLIMITTYPE_CONVENTIONAL &&
                    unit(s.speedUnit) && limit(s.speedLimit)) {
                mapLimit=s.speedLimit;mapUnit=s.speedUnit;
            }
        } else if(topic==4) {
            cameraOff=status!=1 || !Boolean.TRUE.equals(value);
            if(cameraOff)clearCamera();
        } else if(topic==10 && status==1 && value!=null) {
            cameraBlind=((TSDSystemMessages)value).cameraBlind;
            if(cameraBlind)clearCamera();
        } else if(topic>=5 && topic<=9) {
            int slot=topic-5;
            cameraLimit[slot]=cameraUnit[slot]=-1;
            if(status!=1 || value==null || cameraOff || cameraBlind)return;
            TSDSignFct sign=(TSDSignFct)value;
            TSDSignInfo info=sign.signInfo;
            boolean speedSign=sign.sign==DSICarDriverAssistance.TSDSIGN_SPEEDLIMITNARUSA ||
                sign.sign==DSICarDriverAssistance.TSDSIGN_SPEEDLIMITVARNARUSA ||
                sign.sign==DSICarDriverAssistance.TSDSIGN_SPEEDLIMITNARCANADA ||
                sign.sign==DSICarDriverAssistance.TSDSIGN_SPEEDLIMITVARNARCANADA;
            boolean unconditional=(sign.addSign==DSICarDriverAssistance.TSDADDSIGN_NOSIGN ||
                sign.addSign==DSICarDriverAssistance.TSDADDSIGN_EMPTYADDSIGNEU) &&
                (sign.addSignDynamicValue==null || sign.addSignDynamicValue.length()==0);
            if(speedSign && unconditional && info!=null && info.sourceIsCamera && !info.sourceIsDatabase &&
                    !info.sourceIsFusion && limit(sign.signDynamicValue)) {
                // signEffective is carried but has no established applicability contract.
                cameraLimit[slot]=sign.signDynamicValue;cameraUnit[slot]=info.mph?1:0;
            }
        }
    }
    private static double convert(double value,int from,int to) {
        return from==to?value:from==1?value*1.609344:value/1.609344;
    }
    Snapshot copy(long now,int connection) {
        Snapshot out=new Snapshot();
        out.enabled=true;out.connection=connection;out.wide=wideMap;
        int selected=mapLimit,selectedUnit=mapUnit;
        out.source=mapLimit>=0?1:0;
        boolean ambiguous=false;
        for(int i=0;i<cameraLimit.length;i++)if(cameraLimit[i]>=0) {
            if(out.source==2 && Math.abs(convert(selected,selectedUnit,0)-
                    convert(cameraLimit[i],cameraUnit[i],0))>0.5)ambiguous=true;
            selected=cameraLimit[i];selectedUnit=cameraUnit[i];out.source=2;
        }
        if(ambiguous){selected=mapLimit;selectedUnit=mapUnit;out.source=mapLimit>=0?1:0;}
        if(out.source==2 && mapLimit>=0)
            out.conflict=Math.abs(convert(selected,selectedUnit,0)-convert(mapLimit,mapUnit,0))>0.5;
        int lease=speed==0?STOPPED_LEASE_MS:MOVING_LEASE_MS;
        boolean fresh=speed>=0 && now>=speedAt && now-speedAt<lease;
        out.unit=fresh?speedUnit:selectedUnit;
        if(fresh){out.speed=Math.round(speed);out.expires=speedAt+lease;}
        if(selected>=0) {
            int shown=(int)Math.round(convert(selected,selectedUnit,out.unit));
            if(shown>0 && shown<=MAX_VALUE)out.limit=shown;
            else out.source=0;
        }
        // Match the visible integers: equal displayed numbers never produce a red warning.
        out.over=out.speed>=0 && out.limit>0 && out.speed>out.limit;
        return out;
    }
}
