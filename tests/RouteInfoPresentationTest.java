import com.luka.carplay.core.ScreenModule;
import com.luka.carplay.rgd.BAPBridge;
import com.luka.carplay.rgd.RouteGuidance;
import de.audi.atip.interapp.combi.bap.navi.CombiBAPServiceNavi;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.TimeZone;

/** Exercises the shipping BAPBridge, not a copy of the presentation algorithm. */
public final class RouteInfoPresentationTest {
    private static int checks;

    private static final class Output implements InvocationHandler {
        String position, turnTo, signPost;
        int positionCalls, turnCalls, timeCalls, timeType;
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("updateCurrentPositionInfo")) {
                position = (String) args[0];
                positionCalls++;
            } else if (name.equals("updateTurnToInfo")) {
                turnTo = (String) args[0];
                signPost = (String) args[1];
                turnCalls++;
            } else if (name.equals("updateTimeToDestination")) {
                timeType = ((Integer) args[0]).intValue();
                timeCalls++;
            } else {
                throw new AssertionError("Unexpected BAP write: " + name);
            }
            return null;
        }
    }

    private static void set(Class owner, Object target, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void equal(String label, Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual))
            throw new AssertionError(label + ": expected <" + expected + ">, got <" + actual + ">");
    }

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static RouteGuidance.State route() {
        RouteGuidance.State state = new RouteGuidance.State();
        state.maneuverOrder = new int[] {0};
        state.mType[0] = 1;
        state.mAfterRoad[0] = "Road: Main Street";
        state.mName[0] = "Maneuver name";
        state.mExitInfo[0] = "Exit 12: Main Street";
        state.currentRoad = "Current Road";
        return state;
    }

    private static Output render(BAPBridge bridge, Output output,
            RouteGuidance.State state, boolean small, int phase) throws Exception {
        set(ScreenModule.class, null, "smallScreenViewArea", Boolean.valueOf(small)); // target: boolean flag
        output.position = output.turnTo = output.signPost = "STALE";
        output.positionCalls = output.turnCalls = output.timeCalls = 0;
        bridge.refreshInfoPresentation(state, phase);
        equal("one position publish", Integer.valueOf(1), Integer.valueOf(output.positionCalls));
        equal("clear separate text layer", Integer.valueOf(1), Integer.valueOf(output.turnCalls));
        equal("TurnTo stays empty", "", output.turnTo);
        equal("SignPost stays empty", "", output.signPost);
        equal("native ETA is republished", Integer.valueOf(1), Integer.valueOf(output.timeCalls));
        equal("native ETA never switches to duration", Integer.valueOf(1), Integer.valueOf(output.timeType));
        check("position never empty during RGI", output.position.length() > 0);
        check("UTF-8 wire budget", output.position.getBytes("UTF-8").length <= 96);
        check("no synthetic arrow", output.position.indexOf('\u2191') < 0);
        for (int i = 0; i < output.position.length(); i++) {
            char c = output.position.charAt(i);
            if (Character.isHighSurrogate(c)) {
                check("complete supplementary character", i + 1 < output.position.length()
                    && Character.isLowSurrogate(output.position.charAt(++i)));
            } else check("no orphan low surrogate", !Character.isLowSurrogate(c));
        }
        return output;
    }

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Output output = new Output();
        BAPBridge bridge = new BAPBridge();
        CombiBAPServiceNavi service = (CombiBAPServiceNavi) Proxy.newProxyInstance(
            RouteInfoPresentationTest.class.getClassLoader(),
            new Class[] {CombiBAPServiceNavi.class}, output);
        set(BAPBridge.class, bridge, "appConnectorNavi", service);
        set(BAPBridge.class, bridge, "initialized", Boolean.TRUE);
        set(BAPBridge.class, bridge, "bapSessionStarted", Boolean.TRUE);

        for (int view = 0; view < 2; view++) {
            boolean small = view == 1;
            RouteGuidance.State state = route();
            equal("signpost priority, no road stripping", "\u2039Exit 12: Main Street\u203A",
                render(bridge, output, state, small, 0).position);
            equal("unknown ETA retains navigation", "\u2039Exit 12: Main Street\u203A",
                render(bridge, output, state, small, 1).position);
            state.mExitInfo[0] = " \t\n";
            equal("next road fallback", "\u25CF Main Street", render(bridge, output, state, small, 0).position);
            state.mAfterRoad[0] = repeat("\u0416", 70);
            String wide = render(bridge, output, state, small, 0).position;
            check("wide Cyrillic fits pixels before wire limit", wide.startsWith("\u25CF ")
                && wide.length() > 3 && wide.length() < 20);
            state.mAfterRoad[0] = " \t";
            equal("maneuver name fallback", "\u25CF Maneuver name", render(bridge, output, state, small, 0).position);
            state.mName[0] = null;
            equal("current road fallback", "Current Road", render(bridge, output, state, small, 0).position);
            state.currentRoad = null;
            equal("pending, not empty or fake arrow", "\u2026", render(bridge, output, state, small, 0).position);
            equal("unknown ETA and road", "\u2026", render(bridge, output, state, small, 1).position);
            state = route();
            state.maneuverOrder = new int[0];
            equal("empty list ignores stale maneuver slots", "Current Road",
                render(bridge, output, state, small, 0).position);
            state = route();
            state.mExitInfo[0] = "  Exit\n12\t Main  Street  ";
            equal("transport whitespace", "\u2039Exit 12 Main Street\u203A", render(bridge, output, state, small, 0).position);
            String[] longSigns = {repeat("x", 120), repeat("\u0416", 70),
                repeat("\u9053", 31), repeat("x", 89) + "\uD83D\uDE97z",
                repeat("x", 86) + "\uD83D\uDE97z"};
            for (int i = 0; i < longSigns.length; i++) {
                state.mExitInfo[0] = longSigns[i];
                String frame = render(bridge, output, state, small, 0).position;
                check("every long signpost retains both quotes", frame.startsWith("\u2039") && frame.endsWith("\u203A"));
                check("overflow is fitted in pixels, not only bytes", frame.length() < longSigns[i].length());
            }
            state.mExitInfo[0] = "Cafe\u0301";
            equal("composed accent reaches the firmware font", "\u2039Caf\u00E9\u203A",
                render(bridge, output, state, small, 0).position);
        }

        RouteGuidance.State state = route();
        set(BAPBridge.class, bridge, "lastEtaSeconds", Long.valueOf(System.currentTimeMillis() / 1000L + 2220));
        check("small duration retains arrival",render(bridge, output, state, true, 1).position.startsWith("\u25CC 37 min | "));
        byte[] wire = output.position.getBytes("UTF-8");
        check("exact U+25CC wire prefix", (wire[0] & 255) == 0xE2
            && (wire[1] & 255) == 0x97 && (wire[2] & 255) == 0x8C && wire[3] == 0x20);
        String full = render(bridge, output, state, false, 1).position;
        check("full marked arrival and duration in one field", full.startsWith("\u25CC ") && full.endsWith(" | 37 min"));
        check("full marker appears only once", full.indexOf('\u25CC', 1) < 0);
        String road=render(bridge,output,state,false,0).position;
        check("Road retains arrival beside clipped navigation: "+road,
            road.startsWith("\u2039Exit ") && road.contains("\u203A | "));
        set(BAPBridge.class, bridge, "lastEtaSeconds", Long.valueOf(-1));
        set(BAPBridge.class, bridge, "lastTimeRemainingSeconds", Long.valueOf(3900));
        set(BAPBridge.class, bridge, "lastTimeRemainingSampleSeconds", Long.valueOf(System.currentTimeMillis() / 1000L));
        check("duration-only input and long-trip format",
            render(bridge, output, state, true, 1).position.startsWith("\u25CC 1 h 05 min | "));
        full = render(bridge, output, state, false, 1).position;
        check("duration-only input produces arrival too", full.startsWith("\u25CC ") && full.endsWith(" | 1 h 05 min"));
        set(BAPBridge.class, bridge, "lastEtaSeconds", Long.valueOf(1));
        check("past ETA clamps at zero",render(bridge, output, state, true, 1).position.startsWith("\u25CC 0 min | "));
        state.etaSeconds=36000;state.destinationTimeZoneMinutes=120;
        state.dirtyMask=RouteGuidance.State.DIRTY_ETA|RouteGuidance.State.DIRTY_DEST_TIMEZONE;
        state.mExitInfo[0]=repeat("x",120);
        check("Road ETA uses destination zone",
            render(bridge,output,state,false,0).position.endsWith("\u203A | 12:00 dest"));
        Method tick=BAPBridge.class.getDeclaredMethod("tickPositionScroll",long.class);
        tick.setAccessible(true);
        long now=System.currentTimeMillis();
        for(int i=1;i<=12;i++) {
            tick.invoke(bridge,now+i*2000L);
            check("each scrolling fragment retains ETA",output.position.endsWith("\u203A | 12:00 dest"));
            check("ETA fits byte budget",output.position.getBytes("UTF-8").length<=96);
        }
        full=render(bridge,output,state,false,1).position;
        check("generated Trip clock is not duplicated",full.indexOf("12:00")==full.lastIndexOf("12:00"));
        state.mExitInfo[0]="Exit 12 | ETA: 08:15";
        String supplied=render(bridge,output,state,false,0).position;
        check("phone-supplied clock normalized without duplicate",supplied.contains(" | 08:15")
            && !supplied.contains("ETA") && !supplied.contains("12:00"));
        state.mExitInfo[0]="BETA Road";
        check("ordinary road name remains intact",render(bridge,output,state,false,0).position.contains("BETA Road"));
        System.out.println("RouteInfoPresentationTest: PASS (" + checks + " checks)");
    }
}
