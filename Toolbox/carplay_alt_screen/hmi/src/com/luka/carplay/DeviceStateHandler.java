package com.luka.carplay;

import de.audi.app.terminalmode.device.IActiveDeviceStateListener;
import de.audi.app.terminalmode.device.TMDevice;

/** Source-built OEM listener; late callbacks retain their activation identity. */
final class DeviceStateHandler implements IActiveDeviceStateListener {
    private final int generation;
    DeviceStateHandler(int generation) { this.generation = generation; }
    public void updateActiveDeviceState(TMDevice device) {
        CarPlayHook.onDeviceStateUpdate(device, generation);
    }
}
