package com.fakegps;

public class FakeGpsNative {
    static {
        System.loadLibrary("fakegps");
    }

    public static native void setFakeLocation(double lat, double lon, double alt, float accuracy);
    public static native void enable();
    public static native void disable();
    public static native int isEnabled();
    public static native String getFakeLocationString();
    public static native int isHookInstalled();
}
