package de.audi.app.terminalmode.smartphone.carplay;

import de.audi.app.terminalmode.smartphone.IPhoneCallController;

public final class PhoneControllerFixture implements IPhoneCallController {
    public int hooks, hangups, flashes;
    public void hook(boolean pressed) { hooks++; }
    public void hangup(boolean pressed) { hangups++; }
    public void flash(boolean pressed) { flashes++; }
}
