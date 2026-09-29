import com.luka.carplay.core.SteeringWheelInputModule;
import de.audi.app.terminalmode.keyevents.KeyState;
import java.lang.reflect.Field;
import org.dsi.ifc.keypanel.DSIKeyPanelListener;

public final class SteeringWheelTraceTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args) throws Exception {
        SteeringWheelInputModule module=new SteeringWheelInputModule();
        Field running=SteeringWheelInputModule.class.getDeclaredField("running");
        running.setAccessible(true);running.setBoolean(module,true);
        Field f=SteeringWheelInputModule.class.getDeclaredField("listener");f.setAccessible(true);
        DSIKeyPanelListener listener=(DSIKeyPanelListener)f.get(module);
        listener.updateKey2(4,40,1,1,1);
        check(SteeringWheelInputModule.consumeCollapsedSelect(KeyState.PRESSED),"existing roller press changed");
        listener.updateKey2(4,40,0,2,1);
        check(SteeringWheelInputModule.consumeCollapsedSelect(KeyState.RELEASED),"existing roller release changed");
        for(int key:new int[]{36,37,38,39,41,99,100})for(int state:new int[]{0,1,2,3,4,5}) {
            listener.updateKey2(4,key,state,3,1);
            check(!SteeringWheelInputModule.consumeCollapsedSelect(KeyState.PRESSED),"trace consumed unrelated wheel key");
            check(!SteeringWheelInputModule.consumeCollapsedSelect(KeyState.RELEASED),"trace consumed unrelated release");
            check(!SteeringWheelInputModule.consumePanelBack(KeyState.PRESSED),"closed panel consumed Back");
            check(!SteeringWheelInputModule.consumePanelBack(KeyState.RELEASED),"closed panel consumed Back release");
        }
        listener.updateKey2(4,40,3,4,1);
        check(SteeringWheelInputModule.consumeCollapsedSelect(KeyState.LONGPRESSED),"wheel long press leaked to CarPlay");
        listener.updateKey2(4,40,3,4,1);
        listener.updateKey2(1,16,3,5,1);
        check(!SteeringWheelInputModule.consumeCollapsedSelect(KeyState.LONGPRESSED),"centre knob long press affected");
        listener.updateEncoder2(4,40,3,0,1);
        check(!SteeringWheelInputModule.consumeCollapsedSelect(KeyState.PRESSED),"encoder acquired input");
        listener.updateKey2(4,40,1,5,1);
        listener.updateKey2(1,16,1,6,1);
        check(!SteeringWheelInputModule.consumeCollapsedSelect(KeyState.PRESSED),"centre knob affected by wheel trace");
        listener.updateKey2(4,40,1,7,0);
        check(!SteeringWheelInputModule.consumeCollapsedSelect(KeyState.PRESSED),"invalid update consumed");
        running.setBoolean(module,false);
        listener.updateKey2(4,40,1,8,1);
        check(!SteeringWheelInputModule.consumeCollapsedSelect(KeyState.PRESSED),"stopped listener consumed");
        System.out.println("SteeringWheelTraceTest: wheel-origin short/long suppression, centre DDS, observe-only encoder and invalid/stopped gates PASS");
    }
}
