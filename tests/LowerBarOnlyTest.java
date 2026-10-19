package com.luka.carplay.routeguidance;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.core.ScreenNavStatusGate;
import com.luka.carplay.framework.Log;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import java.lang.reflect.*;
import java.util.*;

public final class LowerBarOnlyTest implements InvocationHandler {
    final Map<String,Integer> calls=new HashMap<>();
    String text;
    boolean textOnly,failText;
    public Object invoke(Object proxy,Method method,Object[] args) {
        String name=method.getName();
        if(textOnly && !name.equals("updateCurrentPositionInfo") && !name.equals("updateTurnToInfo"))
            throw new AssertionError("Text-only bridge wrote "+name);
        if(name.equals("updateCurrentPositionInfo")) {
            if(failText){failText=false;throw new IllegalStateException("injected text failure");}
            text=(String)args[0];
        }
        calls.put(name,count(name)+1);return null;
    }
    int count(String name){Integer n=calls.get(name);return n==null?0:n;}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static Field field(Class<?> owner,String name) throws Exception {
        Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;
    }
    static void installed(GatedCombiService gate) throws Exception {
        gate.setCurrentPositionInfoBlocked(true,true);
        field(ScreenNavStatusGate.class,"gate").set(null,gate);
        field(ScreenNavStatusGate.class,"desiredCurrentPositionBlocked").setBoolean(null,true);
        field(ScreenNavStatusGate.class,"desiredCurrentPositionTextOnly").setBoolean(null,true);
        field(ScreenNavStatusGate.class,"policyRevision").setInt(null,7);
        field(ScreenNavStatusGate.class,"appliedRevision").setInt(null,7);
        field(ScreenNavStatusGate.class,"installScheduled").setBoolean(null,false);
    }
    public static void main(String[] args) throws Exception {
        Log.setLevel(-1);
        LowerBarOnlyTest output=new LowerBarOnlyTest();
        CombiBAPServiceNavi service=(CombiBAPServiceNavi)Proxy.newProxyInstance(
            LowerBarOnlyTest.class.getClassLoader(),new Class<?>[]{CombiBAPServiceNavi.class},output);
        GatedCombiService gate=new GatedCombiService(service);
        gate.setCurrentPositionInfoBlocked(true);
        gate.updateCurrentPositionInfo("OEM road");gate.updateTurnToInfo("OEM turn","OEM sign");
        gate.updateDistanceToDestination(3,0,true);gate.updateTimeToDestination(1,0,36000);
        gate.updateDestinationInfo(null);
        gate.tightenCurrentPositionInfo(true);
        check(output.calls.isEmpty(),"worker tightening replayed native fields off NavigationJobs");
        gate.setCurrentPositionInfoBlocked(true,true);
        check(output.count("updateDistanceToDestination")==1 && output.count("updateTimeToDestination")==1
            && output.count("updateDestinationInfo")==1 && output.count("updateCurrentPositionInfo")==0,
            "narrowing must return Fct21/22/46, not Fct19/20");
        gate.updateRGStatus(1);gate.updateManeuverState(4);
        check(output.count("updateRGStatus")==1 && output.count("updateManeuverState")==1,
            "text mode must not claim native route/maneuver output");
        gate.setCurrentPositionInfoBlocked(false);
        check(output.text.equals("OEM road") && output.count("updateTurnToInfo")==1,
            "release must replay native text");

        installed(gate);output.calls.clear();output.textOnly=true;
        RouteGuidance rg=new RouteGuidance(true);
        check(rg.init(service) && rg.engageTakeover(),"text route initialization");
        BAPBridge bridge=(BAPBridge)field(RouteGuidance.class,"bap").get(rg);
        check(!bridge.preparePresentation(),"text readiness before publication");
        field(RouteGuidance.class,"running").setBoolean(rg,true);
        field(com.luka.carplay.cluster.ClusterStateController.class,"rgiPresentationActive").setBoolean(null,false);
        byte[] frame="route_state:n:1\ncurrent_road:s:Main Street\neta_seconds:n:36000\ndist_dest_m:n:300\n"
            .getBytes("UTF-8");
        rg.onFrame(CarplayBus.EVT_RGD_UPDATE,0,frame,frame.length);
        check(output.text!=null && output.text.startsWith("Main") && output.text.contains(" | "),
            "real RGI frame must publish Road and ETA");
        check(bridge.isPresentationReady() && !ScreenModule.isNavActive(),"text must not claim RGI context");
        field(com.luka.carplay.cluster.ClusterStateController.class,"carPlaySessionActive").setBoolean(null,true);
        ScreenModule.setInfoModeListener((ScreenModule.InfoModeListener)field(RouteGuidance.class,"infoModeListener").get(rg));
        ScreenModule.onSteeringWheelOkPressed();
        check(field(RouteGuidance.class,"desiredInfoPhase").getInt(rg)==1,"text-only Road/Trip toggle was blocked");
        field(com.luka.carplay.cluster.ClusterStateController.class,"carPlaySessionActive").setBoolean(null,false);
        check(field(BAPBridge.class,"rendererClient").get(bridge)==null
            && field(BAPBridge.class,"csRef").get(bridge)==null
            && !field(BAPBridge.class,"nativeStopAttempted").getBoolean(bridge),
            "text mode touched renderer/native navigation/KOMO");
        RouteGuidance.State state=(RouteGuidance.State)field(RouteGuidance.class,"state").get(rg);
        check(bridge.refreshInfoPresentation(state,1),"text-only Trip refresh");
        check(output.count("updateTimeToDestination")==0,"text mode wrote destination clock Fct22");
        field(com.luka.carplay.cluster.ClusterStateController.class,"rgiPresentationActive").setBoolean(null,true);
        frame="route_state:n:0\n".getBytes("UTF-8");
        rg.onFrame(CarplayBus.EVT_RGD_UPDATE,0,frame,frame.length);
        check("".equals(output.text) && ScreenModule.isNavActive(),"route end changed non-owned context");
        rg.stop();rg.disengageTakeover();
        check(!field(ScreenNavStatusGate.class,"desiredRouteBlocked").getBoolean(null),"text mode gated native RG");

        installed(gate);
        output.failText=true;
        check(!bridge.onStart() && !bridge.isPresentationReady(),"failed text startup looked ready");
        check(!field(ScreenNavStatusGate.class,"desiredCurrentPositionBlocked").getBoolean(null),
            "failed text startup retained ownership");
        System.out.println("LowerBarOnlyTest: real route text/ETA, partitioned replay, renderer/context isolation and startup rollback PASS");
    }
}
