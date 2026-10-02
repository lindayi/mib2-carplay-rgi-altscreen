package com.luka.carplay.core;

import org.dsi.ifc.cardriverassistance.*;
import org.dsi.ifc.trafficregulation.*;

public final class SpeedBadgeTest {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    static TrafficSignInformation map(int value,int unit,int type) {
        TrafficSignInformation s=new TrafficSignInformation();
        s.highestPrioritySpeedLimit=new SpeedLimitInfo(value,type,unit);return s;
    }
    static TSDSignFct camera(int value,boolean mph) {
        TSDSignFct s=new TSDSignFct();s.sign=mph?49:65;s.signDynamicValue=value;
        s.signInfo=new TSDSignInfo();s.signInfo.sourceIsCamera=true;s.signInfo.mph=mph;
        return s;
    }
    static void selection() {
        SpeedBadge model=new SpeedBadge();
        SpeedBadge.Snapshot s=model.copy(1000,7);
        check(s.speed==-1 && s.limit==-1 && s.source==0 && !s.over,"unknown must not warn");
        model.update(2,map(50,0,1),1,1000);
        check(model.copy(1000,7).source==1,"map fallback absent camera");
        TSDSignFct camera=camera(40,false);
        model.update(5,camera,1,1000);
        camera.signDynamicValue=90;
        s=model.copy(1000,7);
        check(s.limit==40 && s.source==2 && s.conflict,"copied camera-first despite effective=false");
        model.update(0,SpeedSourceDiagnosticsTest.speed(41,0,1),1,1000);
        check(model.copy(1000,7).over,"camera limit participates in red comparison");
        model.update(0,SpeedSourceDiagnosticsTest.speed(40,0,1),1,1000);
        check(!model.copy(1000,7).over,"equal must remain white");
        model.update(0,SpeedSourceDiagnosticsTest.speed(39,0,1),1,1000);
        check(!model.copy(1000,7).over,"below must remain white");
        for(int sign:new int[]{0,67,73,15,1,999}) {
            TSDSignFct canceled=camera(40,false);canceled.sign=sign;
            model.update(5,canceled,1,1000);
            check(model.copy(1000,7).source==1,"unsupported/cancel sign must clear camera");
        }
        for(int condition:new int[]{2,4,6,12,13,14,999}) {
            camera=camera(40,false);camera.addSign=condition;model.update(5,camera,1,1000);
            check(model.copy(1000,7).source==1,"conditional sign must not be treated as unconditional");
        }
        camera=camera(40,false);camera.addSignDynamicValue="private condition";
        model.update(5,camera,1,1000);check(model.copy(1000,7).source==1,"condition text must disqualify sign");
        camera=camera(40,false);camera.signInfo.sourceIsDatabase=true;
        model.update(5,camera,1,1000);check(model.copy(1000,7).source==1,"ambiguous source must not masquerade as camera");
        camera=camera(40,false);camera.signInfo.sourceIsFusion=true;
        model.update(5,camera,1,1000);check(model.copy(1000,7).source==1,"fusion is not independently verified camera");
        model.update(5,camera(40,false),1,1000);
        model.update(6,camera(50,false),1,1000);
        check(model.copy(1000,7).source==1,"different camera slots have no established priority");
        model.update(6,null,2,1000);
        check(model.copy(1000,7).source==2,"invalid slot4/5 must not invalidate a valid slot1");
        model.update(4,Boolean.FALSE,1,1000);model.update(5,camera(40,false),1,1000);
        check(model.copy(1000,7).source==1,"system Off clears and blocks camera");
        model.update(4,Boolean.TRUE,1,1000);
        check(model.copy(1000,7).source==1,"reenabling cannot resurrect old sign");
        model.update(5,camera(40,false),1,1000);
        TSDSystemMessages blind=new TSDSystemMessages();blind.cameraBlind=true;
        model.update(10,blind,1,1000);
        check(model.copy(1000,7).source==1,"explicit blind camera withdraws sign");
        blind.cameraBlind=false;model.update(10,blind,1,1000);
        model.update(5,camera(40,false),1,1000);
        check(model.copy(999999,7).limit==40,"change-driven sign cannot use numeric-speed timeout");
        model.update(5,null,2,1000);
        model.update(0,SpeedSourceDiagnosticsTest.speed(51,0,1),1,1000);
        check(model.copy(1000,7).over && model.copy(1000,7).source==1,"map fallback participates in red comparison");
        for(int type:new int[]{0,2,99}) {
            model.update(2,map(50,0,type),1,1000);
            check(model.copy(1000,7).limit==-1,"advisory/unknown type is not a conventional limit");
        }
        model.update(2,map(50,0,1),1,1000);model.update(2,map(-1,1,1),1,1000);
        check(model.copy(1000,7).limit==-1,"map unknown must clear, not change to mph");
        for(int source=0;source<3;source++)model.reset(source);
        check(model.copy(1000,8).speed==-1 && model.copy(1000,8).source==0,"source reset retained data");
    }
    static void speedAndUnits() {
        SpeedBadge model=new SpeedBadge();
        model.update(2,map(50,1,1),1,1000);
        model.update(0,SpeedSourceDiagnosticsTest.speed(81,0,1),1,1000);
        SpeedBadge.Snapshot s=model.copy(1000,9);
        check(s.unit==0 && s.limit==80 && s.speed==81 && s.over,"mph limit conversion to vehicle km/h");
        model.update(5,camera(50,false),1,1000);
        model.update(0,SpeedSourceDiagnosticsTest.speed(32,1,1),1,1000);
        s=model.copy(1000,9);
        check(s.unit==1 && s.limit==31 && s.over,"km/h camera converted to vehicle mph");
        model.update(0,SpeedSourceDiagnosticsTest.speed(31.1f,1,1),1,1000);
        check(!model.copy(1000,9).over,"comparison must match visible rounded integers");
        check(model.copy(2999,9).speed==31 && model.copy(3000,9).speed==-1,"moving sample lease");
        check(!model.copy(3001,9).over && model.copy(3001,9).source==2,"stale speed cannot warn; sign remains");
        check(model.copy(999,9).speed==-1,"clock reversal cannot refresh speed");
        model.update(0,SpeedSourceDiagnosticsTest.speed(0,0,1),1,1000);
        check(model.copy(15999,9).speed==0 && model.copy(16000,9).speed==-1,"bounded parked zero lease");
        for(float value:new float[]{-1,400,Float.NaN,Float.POSITIVE_INFINITY}) {
            model.update(0,SpeedSourceDiagnosticsTest.speed(value,0,1),1,1000);
            check(model.copy(1000,9).speed==-1,"invalid numeric speed");
        }
        model.update(0,SpeedSourceDiagnosticsTest.speed(40,7,1),1,1000);
        check(model.copy(1000,9).speed==-1,"unknown speed unit");
        model.update(0,SpeedSourceDiagnosticsTest.speed(40,0,0),1,1000);
        check(model.copy(1000,9).speed==-1,"invalid value-state");
        model.update(0,SpeedSourceDiagnosticsTest.speed(40,0,1),2,1000);
        check(model.copy(1000,9).speed==-1,"invalid callback-state");
        SpeedBadge.presentation(true);check(model.copy(1000,9).wide,"accepted large-map placement");
        SpeedBadge.presentation(false);check(!model.copy(1000,9).wide,"large-dial inset");
    }
    public static void main(String[] args) {
        selection();speedAndUnits();
        System.out.println("SpeedBadgeTest PASS: camera-first/map fallback, no guessed effectiveness gate, clear/conditional/source/units/freshness/thresholds");
    }
}
