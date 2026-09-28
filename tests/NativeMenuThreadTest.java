import com.luka.carplay.settings.CarplayMenuController;
import de.audi.atip.hmi.event.ATIPEvent;
import de.audi.atip.hmi.event.EventDispatcher;
import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public final class NativeMenuThreadTest {
    static final Thread ui=Thread.currentThread();
    static final List<ATIPEvent> events=new ArrayList<>();
    static boolean reject;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void field(Object target,String name,Object value) throws Exception {
        Field f=CarplayMenuController.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
    static final class Menu extends CarplayMenuController {
        int renders,paints;
        protected void refreshMenu() {
            check(Thread.currentThread()==ui,"native update executed on worker");
            renders++;
        }
        public void triggerRepaint() {
            check(Thread.currentThread()==ui,"worker called synchronous Audi repaint directly");
            paints++;
        }
        public void clearAllCaches(){check(Thread.currentThread()==ui,"worker cleared native caches");}
        public void relayout(){check(Thread.currentThread()==ui,"worker changed native layout");}
        void disconnect(){disconnectUpdates();}
    }
    static void worker(Runnable task) throws Exception {
        final Throwable[] error={null};
        Thread t=new Thread(()->{try{task.run();}catch(Throwable e){error[0]=e;}},"fixture-settings-worker");
        t.start();t.join(2000);check(!t.isAlive(),"notification blocked worker");
        if(error[0]!=null)throw new AssertionError(error[0]);
    }
    public static void main(String[] args) throws Exception {
        ClassNode bytecode=new ClassNode();
        new ClassReader("com.luka.carplay.settings.CarplayMenuController").accept(bytecode,0);
        for(MethodNode method:bytecode.methods) {
            check(!method.name.equals("managePaint"),"custom tree mutation must not run during native painting");
            if(method.name.equals("changed"))for(AbstractInsnNode insn:method.instructions) {
                if(insn instanceof MethodInsnNode) {
                    String name=((MethodInsnNode)insn).name;
                    check(!name.equals("triggerRepaint") && !name.equals("rebuild") && !name.equals("refreshMenu"),
                        "listener synchronously invokes native work");
                }
            }
        }
        Constructor<?> ctor=Class.forName("com.luka.carplay.rgd.BAPBridge$SilentLogChannel").getDeclaredConstructor();
        ctor.setAccessible(true);Object log=ctor.newInstance();
        de.audi.atip.base.IFrameworkAccess fw=(de.audi.atip.base.IFrameworkAccess)Proxy.newProxyInstance(
            NativeMenuThreadTest.class.getClassLoader(),new Class[]{de.audi.atip.base.IFrameworkAccess.class},(p,m,a)->{
                if(m.getName().equals("getLogChannel"))return log;
                if(m.getReturnType()==boolean.class)return true;
                if(m.getReturnType()==int.class)return 0;
                if(m.getReturnType()==long.class)return 0L;
                return null;
            });
        de.esolutions.hmi.widgets.audi.base.AbstractWidget.setFrameworkAccess(fw);
        EventDispatcher dispatcher=(EventDispatcher)Proxy.newProxyInstance(
            NativeMenuThreadTest.class.getClassLoader(),new Class[]{EventDispatcher.class},(p,m,a)->{
                if(m.getName().equals("postEvent")) {
                    if(reject)throw new IllegalStateException("fixture queue failure");
                    events.add((ATIPEvent)a[0]);return null;
                }
                if(m.getName().equals("isDispatchThread"))return Thread.currentThread()==ui;
                if(m.getReturnType()==boolean.class)return false;
                return null;
            });
        Menu menu=new Menu();
        field(menu,"dispatcher",dispatcher);field(menu,"front",true);field(menu,"page","root");
        Method guard=CarplayMenuController.class.getDeclaredMethod("requireDispatchThread");
        guard.setAccessible(true);
        worker(()->{
            try {guard.invoke(menu);throw new AssertionError("native affinity guard accepted worker");}
            catch(InvocationTargetException e){check(e.getCause() instanceof IllegalStateException,"wrong guard failure");}
            catch(ReflectiveOperationException e){throw new AssertionError(e);}
        });
        worker(()->{for(int i=0;i<100;i++)menu.changed();});
        check(menu.renders==0 && events.size()==1,"updates not deferred/coalesced");
        events.remove(0).dispatch();
        check(menu.renders==1,"real stock RunnableEvent did not deliver update");
        worker(menu::changed);ATIPEvent obsolete=events.remove(0);
        menu.disconnect();
        field(menu,"dispatcher",dispatcher);field(menu,"front",true);field(menu,"page","root");
        worker(menu::changed);
        obsolete.dispatch();
        check(menu.renders==1 && events.size()==1,"old lifecycle event touched reconnected menu");
        events.remove(0).dispatch();check(menu.renders==2,"new lifecycle update lost");
        field(menu,"page","");worker(menu::changed);check(events.isEmpty(),"closed OEM page repainted");
        field(menu,"pageChanged",true);worker(menu::changed);
        check(events.size()==1,"closing custom page did not restore OEM rows");
        events.remove(0).dispatch();field(menu,"pageChanged",false);
        field(menu,"page","root");reject=true;worker(menu::changed);reject=false;
        worker(menu::changed);check(events.size()==1,"queue failure permanently wedged notifications");
        events.remove(0).dispatch();
        worker(()->menu.fail(new IllegalStateException("fixture extension failure")));
        check(menu.paints==0 && events.size()==1,"fallback cleanup ran on worker");
        events.remove(0).dispatch();check(menu.paints==1,"fallback cleanup not dispatched");
        worker(menu::changed);check(events.isEmpty(),"failed extension keeps refreshing");
        System.out.println("NativeMenuThreadTest: actual stock RunnableEvent, worker isolation, coalescing, close/reconnect, queue failure and deferred fallback PASS");
    }
}
