package com.luka.carplay.core;

/** Local feature adapters; the upstream hook owns their single lifecycle. */
public final class LifecycleFeatures {
    private LifecycleFeatures() {}
    public static Module[] create() {
        return new Module[] { new ScreenModule(), new SteeringWheelInputModule(),
            new SpeedSourceDiagnostics(), new com.luka.carplay.telephony.TelephonyModule() };
    }
    public static boolean isPlatformSupported(FrameworkRef framework) {
        return ScreenModule.isPlatformSupported(framework);
    }
}
