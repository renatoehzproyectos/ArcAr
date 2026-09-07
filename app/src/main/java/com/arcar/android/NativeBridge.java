package com.arcar.android;

/**
 * Thin JNI façade over the native GameEngine.
 */
public final class NativeBridge {
    static {
        System.loadLibrary("arcar_jni");
    }

    private NativeBridge() {}

    public static native boolean nativeInit(String meshesDir);
    public static native void nativeShutdown();
    public static native void nativeSetControls(
            float throttle, float steer,
            float pitch, float yaw, float roll,
            boolean jump, boolean boost, boolean handbrake);
    public static native void nativeToggleBallCam();
    public static native void nativeSetBallCam(boolean on);
    public static native void nativeReset();
    public static native void nativeUpdate(float dtSeconds);
    /** out length must be >= 30 */
    public static native boolean nativeGetSnapshot(float[] out);
}
