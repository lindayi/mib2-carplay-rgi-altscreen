/*
 * Duplicate MFL phone-key policy adapted from yuedizhibo V3.8 Fix1 (GPL-3.0).
 * The guarded MU1316 getter weave wraps a value already read by stock.
 * It never replaces/restores the volatile OEM controller field: such writes
 * race the unsynchronized stock active-device assignment.
 */
package com.luka.carplay.telephony;

import com.luka.carplay.framework.Log;
import de.audi.app.terminalmode.smartphone.IPhoneCallController;

public final class StockPhoneKeyFilter {
    private StockPhoneKeyFilter() {}

    public static IPhoneCallController forController(IPhoneCallController real) {
        if (real == null || !real.getClass().getName().startsWith(
                "de.audi.app.terminalmode.smartphone.carplay.")) return real;
        try {
            return new FilteredController(real);
        } catch (Throwable t) {
            Log.w("Telephony", "stock phone-key filter unavailable: " + t);
            return real;
        }
    }

    private static final class FilteredController implements IPhoneCallController {
        private final IPhoneCallController real;
        FilteredController(IPhoneCallController controller) { real = controller; }
        public void hook(boolean pressed) {
            if (!GatedPhoneService.dropStockPhoneKey(13)) real.hook(pressed);
        }
        public void hangup(boolean pressed) {
            if (!GatedPhoneService.dropStockPhoneKey(14)) real.hangup(pressed);
        }
        public void flash(boolean pressed) {
            if (!GatedPhoneService.dropStockPhoneKey(20)) real.flash(pressed);
        }
    }
}
