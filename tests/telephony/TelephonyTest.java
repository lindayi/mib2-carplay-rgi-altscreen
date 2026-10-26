package com.luka.carplay.telephony;

import com.luka.carplay.bus.CarplayBus;
import com.luka.carplay.core.CarPlayApp;
import com.luka.carplay.core.FrameworkRef;
import de.audi.app.terminalmode.IContext;
import de.audi.app.terminalmode.events.*;
import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.interapp.combi.bap.phone.*;
import de.audi.atip.interapp.combi.bap.phone.data.*;
import de.audi.atip.utils.generics.GList;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class TelephonyTest {
    static class Recorder implements InvocationHandler {
        final Map<String,Object[]> last = new ConcurrentHashMap<String,Object[]>();
        final Map<String,Integer> counts = new ConcurrentHashMap<String,Integer>();
        volatile IEventListener listener;
        volatile boolean refuse, dialFail;
        volatile CountDownLatch dialEntered, dialRelease;
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String n = method.getName();
            if (n.equals("hashCode")) return System.identityHashCode(proxy);
            if (n.equals("equals")) return proxy == args[0];
            if (n.equals("toString")) return "Telephony test service";
            if (n.equals("registerListener")) {
                if (refuse) return false;
                listener = (IEventListener)args[0]; return true;
            }
            if (n.equals("unregisterListener")) { listener = null; return true; }
            if (n.equals("callNumber")) {
                if (dialEntered != null) dialEntered.countDown();
                if (dialRelease != null && !dialRelease.await(5, TimeUnit.SECONDS)) throw new AssertionError("dial wait");
                if (dialFail) throw new IllegalStateException("injected dial failure");
            }
            last.put(n, args == null ? new Object[0] : args);
            counts.put(n, count(n) + 1);
            return defaultValue(method.getReturnType());
        }
        int count(String n) { Integer v=counts.get(n); return v == null ? 0 : v; }
        int integer(String n) { return ((Integer)last.get(n)[0]).intValue(); }
    }
    static Object defaultValue(Class<?> c) {
        if (c == Boolean.TYPE) return false;
        if (c == Integer.TYPE) return 0;
        if (c == Long.TYPE) return 0L;
        return null;
    }
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }
    interface Condition { boolean test() throws Exception; }
    static void await(Condition c, String message) throws Exception {
        long end=System.currentTimeMillis()+4000;
        while (!c.test() && System.currentTimeMillis()<end) Thread.sleep(10);
        check(c.test(), message);
    }
    static Field field(Class<?> c, String n) throws Exception {
        Field f=c.getDeclaredField(n); f.setAccessible(true); return f;
    }
    static GList calls(final CallStateChangedEvent... calls) {
        return proxy(GList.class, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) {
                if (m.getName().equals("size")) return calls.length;
                if (m.getName().equals("get")) return calls[((Integer)a[0]).intValue()];
                return defaultValue(m.getReturnType());
            }
        });
    }
    static void frame(Socket socket, int type, String text) throws Exception {
        byte[] body=text.getBytes("UTF-8");
        DataOutputStream out=new DataOutputStream(socket.getOutputStream());
        out.writeInt(CarplayBus.MAGIC); out.writeInt(1); out.writeShort(type);
        out.writeByte(1); out.writeByte(0); out.writeInt(body.length); out.write(body); out.flush();
    }
    static byte[] command(Socket socket) throws Exception {
        DataInputStream in=new DataInputStream(socket.getInputStream());
        for (;;) {
            check(in.readInt()==CarplayBus.MAGIC,"bus magic"); in.readInt();
            int type=in.readUnsignedShort(); in.readUnsignedByte(); in.readUnsignedByte();
            int length=in.readInt(); check(length>=0 && length<=16384,"command bounds");
            byte[] body=new byte[length]; in.readFully(body);
            if (type==CarplayBus.CMD_TEL) return body;
        }
    }
    public static void main(String[] args) throws Exception {
        final Recorder phone=new Recorder(), bluetooth=new Recorder(), events=new Recorder(), address=new Recorder();
        final IEventBus eb=proxy(IEventBus.class,events);
        final IFrameworkAccess framework=proxy(IFrameworkAccess.class,new Recorder() {
            public Object invoke(Object p,Method m,Object[] a) throws Throwable {
                if(m.getName().equals("convertUTCTimeToLocalTime"))return (Long)a[0]-5*3600000L;
                return super.invoke(p,m,a);
            }
        });
        sun.misc.Unsafe unsafe=(sun.misc.Unsafe)field(sun.misc.Unsafe.class,"theUnsafe").get(null);
        final Object manager=unsafe.allocateInstance(Class.forName("de.audi.app.terminalmode.dsi.DSISmartphoneManagerProxy"));
        final Field controller=field(manager.getClass(),"phoneCallController");
        final Method readController=manager.getClass().getDeclaredMethod("access$900",manager.getClass());
        readController.setAccessible(true);
        final de.audi.app.terminalmode.smartphone.carplay.PhoneControllerFixture stockKeys=
            new de.audi.app.terminalmode.smartphone.carplay.PhoneControllerFixture();
        final de.audi.app.terminalmode.smartphone.carplay.PhoneControllerFixture newerKeys=
            new de.audi.app.terminalmode.smartphone.carplay.PhoneControllerFixture();
        controller.set(manager,stockKeys);
        final IContext context=proxy(IContext.class,new InvocationHandler() {
            public Object invoke(Object p,Method m,Object[] a) {
                if (m.getName().equals("getEventBus")) return eb;
                if (m.getName().equals("getFramework")) return framework;
                if (m.getName().equals("getSmartphoneDSIManager")) return manager;
                return defaultValue(m.getReturnType());
            }
        });
        final GatedPhoneService service=new GatedPhoneService(proxy(CombiBAPServicePhone.class,phone));
        final GatedAddressBook adb=new GatedAddressBook(proxy(CombiBAPServiceAddressBook.class,address));
        final CombiBAPServicePhoneListener listener=GatedPhoneListener.wrap(proxy(CombiBAPServicePhoneListener.class,bluetooth));
        final FsgSetup apple=FsgSetup.builder().setMobileConnectionType(5).build();
        service.updateFsgSetup(apple); listener.acceptCall();
        check(bluetooth.count("acceptCall")==1,"Master Off leaves original Bluetooth action");
        check(((FsgSetup)phone.last.get("updateFsgSetup")[0]).getMobileConnectionType()==5,"Off leaves stock FSG");
        service.updateFSGOperationState(7,true,false); service.updateMicMuteState(true);
        service.updateSignalQuality(73); service.updateRegisterState(4,2,3);
        adb.updatePbState(2,17); adb.updateMobileServiceSupport(true,true,true);
        final CarplayBus bus=CarplayBus.getInstance();
        field(CarplayBus.class,"port").setInt(bus,0);
        bus.start();
        await(new Condition(){public boolean test()throws Exception{return field(CarplayBus.class,"serverSocket").get(bus)!=null;}},"bus bind");
        ServerSocket server=(ServerSocket)field(CarplayBus.class,"serverSocket").get(bus);
        Socket receiver=new Socket("127.0.0.1",server.getLocalPort()); receiver.setSoTimeout(4000);
        await(new Condition(){public boolean test(){return bus.connectionGeneration()>=0;}},"receiver connect");
        field(com.luka.carplay.CarPlayHook.class,"active").setBoolean(null,true);
        field(com.luka.carplay.CarPlayHook.class,"fwRef").set(null,new FrameworkRef(context));
        Method recent=GatedPhoneService.class.getDeclaredMethod("recent",int.class,String.class,String.class,int.class,long.class);
        recent.setAccessible(true);
        for(long timestamp:new long[]{3600L,360000000000L}) {
            CombiBAPCallStackEntry entry=(CombiBAPCallStackEntry)recent.invoke(null,0,"","",0,timestamp);
            Calendar expected=Calendar.getInstance(TimeZone.getTimeZone("GMT"));
            expected.setTimeInMillis((timestamp>100000000000L?timestamp:timestamp*1000)-5*3600000L);
            check(entry.getHour()==expected.get(Calendar.HOUR_OF_DAY) && entry.getDay()==expected.get(Calendar.DAY_OF_MONTH),
                "recent-call seconds/milliseconds must use HU local time, including date rollover");
        }
        final TelephonyModule module=new TelephonyModule();
        try {
            events.refuse=true;
            check(!module.start(new FrameworkRef(context)),"refused registration fails closed");
            check(!TelephonyModule.isEnabled(),"failed start leaves stock");
            events.refuse=false;
            check(module.start(new FrameworkRef(context)),"start");
            de.audi.app.terminalmode.smartphone.IPhoneCallController keys=
                (de.audi.app.terminalmode.smartphone.IPhoneCallController)readController.invoke(null,manager);
            check(keys!=stockKeys && controller.get(manager)==stockKeys,
                "woven getter must wrap the snapshot without writing the OEM field");
            keys.flash(true);check(stockKeys.flashes==1,"no-call phone key must remain stock");
            await(new Condition(){public boolean test(){return ((FsgSetup)phone.last.get("updateFsgSetup")[0]).getMobileConnectionType()==3;}},"HFP facade");
            check(address.integer("updatePbState")==0,"CarPlay has no phonebook");
            service.updateCallStates(null,true);
            check(((FsgSetup)phone.last.get("updateFsgSetup")[0]).getMobileConnectionType()==5,"OEM emergency pending withdraws facade");
            check(((Boolean)phone.last.get("updateCallStates")[1]).booleanValue(),"OEM emergency flag preserved");
            listener.confirmEmergencyCall(true);
            check(bluetooth.count("confirmEmergencyCall")==1,"emergency action stays OEM");
            service.updateCallStates(null,false);
            check(((FsgSetup)phone.last.get("updateFsgSetup")[0]).getMobileConnectionType()==3,"facade resumes after OEM emergency clear");
            final IEventListener old=events.listener;
            old.updateCallState(proxy(GList.class, new InvocationHandler() {
                public Object invoke(Object p, Method m, Object[] a) { throw new IllegalStateException("bad event"); }
            }));
            old.updateCallState(calls(new CallStateChangedEvent("123","Caller",3,0,"call-1")));
            await(new Condition(){public boolean test(){
                CombiBAPCallState[] c=(CombiBAPCallState[])phone.last.get("updateCallStates")[0];
                return c!=null&&c[0]!=null&&c[0].getCallState()==1;
            }},"real stock incoming event mapped");
            keys.hook(true);keys.flash(true);keys.hangup(true);
            check(stockKeys.hooks==0 && stockKeys.hangups==0 && stockKeys.flashes==1,
                "live CarPlay call must drop duplicate stock HID actions");
            service.updateCallStates(null,true);
            keys.hangup(true);
            check(stockKeys.hangups==1,"emergency-pending phone key must remain stock");
            service.updateCallStates(null,false);
            controller.set(manager,newerKeys);
            de.audi.app.terminalmode.smartphone.IPhoneCallController newer=
                (de.audi.app.terminalmode.smartphone.IPhoneCallController)readController.invoke(null,manager);
            newer.flash(true);
            check(newerKeys.flashes==0 && controller.get(manager)==newerKeys,
                "new stock assignment must be filtered without an asynchronous re-arm");
            keys.flash(false);
            check(controller.get(manager)==newerKeys,"in-flight old getter must not overwrite newer controller");
            long start=System.nanoTime(); listener.acceptCall();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<100,"BAP action is asynchronous");
            byte[] cmd=command(receiver); check(Arrays.equals(cmd,new byte[]{3,0}),"accept iAP2 command");
            old.updateCallState(calls(new CallStateChangedEvent("123","Caller",1,1,"call-1"),
                new CallStateChangedEvent("456","Other",2,1,"call-2")));
            await(new Condition(){public boolean test(){
                CombiBAPCallState[] c=(CombiBAPCallState[])phone.last.get("updateCallStates")[0];
                return c[0]!=null&&c[0].getCallState()==2&&c[1]!=null&&c[1].getCallState()==5;
            }},"active and held mapping");
            listener.callHold(); check(Arrays.equals(command(receiver),new byte[]{2,1}),"hold");
            listener.resumeCall(); check(Arrays.equals(command(receiver),new byte[]{2,0}),"resume");
            listener.swapCalls(); check(Arrays.equals(command(receiver),new byte[]{5,0}),"swap");
            listener.setMicMuteState(true); check(Arrays.equals(command(receiver),new byte[]{1,1}),"mute");
            listener.hangupCall(0); check(Arrays.equals(command(receiver),new byte[]{4,0}),"hangup");
            frame(receiver,CarplayBus.EVT_TEL_LISTS,"r.n:i:1\nf.n:i:1\nr.0.a:s:123\nr.0.b:s:Caller\nr.0.type:i:3\nr.0.ts:i:12345\nr.0.occ:i:2\nf.0.a:s:456\nf.0.b:s:Favorite\n");
            await(new Condition(){public boolean test(){
                return ((CombiBAPCallStackEntry[])phone.last.get("updateCombinedNumbers")[0]).length==1;
            }},"recents");
            check(((CombiBAPCallStackEntry[])phone.last.get("updateCombinedNumbers")[0])[0].getCallMode()==1,"missed mode");
            check(phone.integer("updateMissedCallIndication")==2,"missed occurrences");
            frame(receiver,CarplayBus.EVT_TEL_LISTS,"r.n:i:2147483647\nf.n:i:1\n");
            Thread.sleep(120);
            check(((CombiBAPCallStackEntry[])phone.last.get("updateCombinedNumbers")[0]).length==1,"oversized list rejected");
            frame(receiver,CarplayBus.EVT_TEL_COMM,"signal:i:4\nregistration:i:5\ncarrier:s:Carrier\nbattery:i:65\n");
            await(new Condition(){public boolean test(){return phone.integer("updateSignalQuality")==80;}},"comm");
            check(phone.integer("updateRegisterState")==4,"roaming");
            events.dialFail=true;
            listener.dialNumber("123","");
            await(new Condition(){public boolean test(){return phone.last.containsKey("dialNumberResult")&&phone.integer("dialNumberResult")==1;}},"dial failure");
            events.dialFail=false;
            listener.dialNumberFromAdbEntry("ignored","",new CombiBAPCallStackEntry(0,"","456",0,0));
            await(new Condition(){public boolean test(){return phone.last.containsKey("dialNumberFromAdbEntryResult");}},"address-book result interface");
            check(phone.integer("dialNumberFromAdbEntryResult")==0,"address-book dial success");
            check(events.last.get("callNumber")[0].equals("456"),"address-book number");
            final int dialResults=phone.count("dialNumberResult");
            final int dialRequests=events.count("callNumber");
            listener.dialNumber("123\nmalformed:uri","");
            await(new Condition(){public boolean test(){return phone.count("dialNumberResult")>dialResults;}},"malformed dial result");
            check(phone.integer("dialNumberResult")==1&&events.count("callNumber")==dialRequests,"invalid number never dispatched");
            service.updateSignalQuality(91); service.updateFSGOperationState(9,false,true);
            events.dialEntered=new CountDownLatch(1); events.dialRelease=new CountDownLatch(1);
            listener.dialNumber("123","");
            check(events.dialEntered.await(2,TimeUnit.SECONDS),"worker entered blocked command");
            final int hangups=phone.count("hangupCallResult");
            for(int i=0;i<17;i++)listener.hangupCall(0);
            check(phone.count("hangupCallResult")==hangups+1&&phone.integer("hangupCallResult")==1,"bounded action queue reports failure");
            start=System.nanoTime();
            field(com.luka.carplay.CarPlayHook.class,"active").setBoolean(null,false); module.stop();
            check(controller.get(manager)==newerKeys,"disconnect must not restore an obsolete controller");
            keys.flash(false);check(stockKeys.flashes==2,"old filter must pass through after disconnect");
            newer.flash(false);check(newerKeys.flashes==1,"latest stock controller must receive post-disconnect keys");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<100,"stop cannot await blocked dial");
            check(phone.integer("updateSignalQuality")==91,"latest Bluetooth signal restored");
            check(phone.integer("updateFSGOperationState")==9,"latest Bluetooth state restored");
            check(address.integer("updatePbState")==2,"Bluetooth directory restored");
            check(((Boolean)phone.last.get("updateMicMuteState")[0]).booleanValue(),"Bluetooth mute restored");
            for(boolean support:(boolean[])phone.last.get("updateMobileServiceSupport")[0])
                check(!support,"uncached capabilities cannot retain fabricated CarPlay support");
            listener.acceptCall(); check(bluetooth.count("acceptCall")==2,"Off restores original action");
            old.updateCallState(calls(new CallStateChangedEvent("late","Late",1,1,"late")));
            field(com.luka.carplay.CarPlayHook.class,"active").setBoolean(null,true);
            check(module.start(new FrameworkRef(context)),"restart");
            int results=phone.count("dialNumberResult");
            events.dialRelease.countDown(); Thread.sleep(200);
            check(phone.count("dialNumberResult")==results,"old generation result rejected");
            old.updateCallState(calls(new CallStateChangedEvent("late","Late",1,1,"late")));
            Thread.sleep(120);
            CombiBAPCallState[] cleared=(CombiBAPCallState[])phone.last.get("updateCallStates")[0];
            check(cleared[0]==null,"old listener cannot seed new session");
            final int failedAccept=phone.count("acceptCallResult");
            listener.acceptCall();
            await(new Condition(){public boolean test(){return phone.count("acceptCallResult")>failedAccept;}},"accept without incoming call");
            check(phone.integer("acceptCallResult")==1,"call action fails without matching call state");
            receiver.close();
            await(new Condition(){public boolean test(){return !TelephonyModule.isEnabled();}},"receiver loss revokes gate");
            listener.acceptCall(); check(bluetooth.count("acceptCall")==3,"receiver loss restores action");
            Class.forName("de.audi.app.combi.bap.app.phone.ServiceManagerPhone");
            Class.forName("de.audi.tghu.phone.sm.Activator");
            Class.forName("de.audi.tghu.system.sm.Activator");
            Class.forName("com.luka.carplay.telephony.CarPlayPhoneSMM");
            Class.forName("com.luka.carplay.telephony.CarPlaySystemSMM");
            System.out.println("TelephonyTest: PASS (exact interfaces, worker, actions, restore, late callbacks)");
        } finally {
            if(events.dialRelease!=null)events.dialRelease.countDown();
            field(com.luka.carplay.CarPlayHook.class,"active").setBoolean(null,false);
            module.stop(); receiver.close(); bus.stop();
        }
    }
}
