package com.arcar.android;

import android.util.Log;

/**
 * Thin JNI façade over the native GameEngine.
 */
public final class NativeBridge {
    private static final String TAG = "ArcArNative";
    private static boolean loaded = false;
    private static String loadError = null;

    static {
        try {
            System.loadLibrary("arcar_jni");
            loaded = true;
            Log.i(TAG, "libarcar_jni.so loaded");
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
            loadError = e.getMessage();
            Log.e(TAG, "Failed to load libarcar_jni.so", e);
        }
    }

    private NativeBridge() {}

    public static boolean isLoaded() { return loaded; }
    public static String getLoadError() { return loadError; }

    private static void ensureLoaded() {
        if (!loaded) {
            throw new UnsatisfiedLinkError("libarcar_jni.so not loaded: " + loadError);
        }
    }

    public static boolean nativeInit(String meshesDir) {
        ensureLoaded();
        return nativeInitImpl(meshesDir);
    }

    public static void nativeShutdown() {
        if (!loaded) return;
        nativeShutdownImpl();
    }

    public static void nativeSetControls(
            float throttle, float steer,
            float pitch, float yaw, float roll,
            boolean jump, boolean boost, boolean handbrake) {
        if (!loaded) return;
        nativeSetControlsImpl(throttle, steer, pitch, yaw, roll, jump, boost, handbrake);
    }

    public static void nativeToggleBallCam() {
        if (!loaded) return;
        nativeToggleBallCamImpl();
    }

    public static void nativeSetBallCam(boolean on) {
        if (!loaded) return;
        nativeSetBallCamImpl(on);
    }

    public static void nativeReset() {
        if (!loaded) return;
        nativeResetImpl();
    }

    public static void nativeUpdate(float dtSeconds) {
        if (!loaded) return;
        nativeUpdateImpl(dtSeconds);
    }

    public static boolean nativeGetSnapshot(float[] out) {
        if (!loaded) return false;
        return nativeGetSnapshotImpl(out);
    }

    private static native boolean nativeInitImpl(String meshesDir);
    private static native void nativeShutdownImpl();
    private static native void nativeSetControlsImpl(
            float throttle, float steer,
            float pitch, float yaw, float roll,
            boolean jump, boolean boost, boolean handbrake);
    private static native void nativeToggleBallCamImpl();
    private static native void nativeSetBallCamImpl(boolean on);
    private static native void nativeResetImpl();
    private static native void nativeUpdateImpl(float dtSeconds);
    private static native boolean nativeGetSnapshotImpl(float[] out);
}
