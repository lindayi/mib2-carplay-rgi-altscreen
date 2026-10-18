import com.luka.carplay.routeguidance.NativeRouteStateOverlay;
import de.audi.tghu.navi.app.command.DSIResponseContainer;
import java.util.concurrent.CountDownLatch;

/** Calls the woven, actual synchronized OEM setter, not a simulated nativeWrite. */
public final class NativeRouteStateOverlayTest {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        DSIResponseContainer a = new DSIResponseContainer(), b = new DSIResponseContainer();
        a.setRgActive(true);
        NativeRouteStateOverlay.apply(a, true);
        a.setRgActive(false);
        check(a.isRgActive(), "native false defeated active overlay");
        NativeRouteStateOverlay.apply(a, true);
        NativeRouteStateOverlay.releaseCurrent();
        check(!a.isRgActive(), "release restored initial rather than latest native false");
        NativeRouteStateOverlay.apply(a, true);
        a.setRgActive(true);
        NativeRouteStateOverlay.releaseCurrent();
        check(a.isRgActive(), "latest native true lost");
        NativeRouteStateOverlay.apply(a, true);
        a.setRgActive(false);
        NativeRouteStateOverlay.apply(b, true);
        check(!a.isRgActive() && b.isRgActive(), "container replacement did not restore old owner");
        a.setRgActive(true);
        b.setRgActive(false);
        NativeRouteStateOverlay.apply(null, false);
        check(a.isRgActive() && !b.isRgActive(), "retained-container release or unrelated native setter");

        final DSIResponseContainer shared = new DSIResponseContainer();
        final CountDownLatch entered = new CountDownLatch(1);
        NativeRouteStateOverlay.apply(shared, true);
        Thread nativeWriter = new Thread(() -> {
            synchronized (shared) {
                shared.setRgActive(false);
                entered.countDown();
                try { Thread.sleep(50); } catch (InterruptedException e) { throw new AssertionError(e); }
            }
        });
        nativeWriter.start(); entered.await();
        NativeRouteStateOverlay.releaseCurrent(); nativeWriter.join(1000);
        check(!nativeWriter.isAlive() && !shared.isRgActive(), "release raced or deadlocked native monitor");
        System.out.println("NativeRouteStateOverlayTest PASS");
    }
}
