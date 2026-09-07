package com.arcar.android;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * Intentionally has no UI. This Activity's only job is to be the launchable
 * entry point of the APK; it hands off immediately to native code.
 *
 * All ArcAr logic (Arena creation, simulation ticks, etc.) lives in
 * src/main/cpp/jni_bridge.cpp. Output is written to Logcat under tag
 * "ArcArNative" - view it with:
 *
 *   adb logcat -s ArcArNative
 */
public class MainActivity extends Activity {

    static {
        // Loads libArcArLib.so + libarcar_jni.so as built by CMake.
        System.loadLibrary("arcar_jni");
    }

    // Implemented in jni_bridge.cpp. Runs a short ArcAr benchmark/demo
    // and returns a human-readable summary string. meshesDir is where
    // ArcAr will look for real Rocket League arena collision meshes.
    private static native String runArcAr(String meshesDir);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String meshesDir = getFilesDir().getAbsolutePath() + "/collision_meshes";

        // Run on a background thread so we never block/ANR the main thread,
        // even though there is nothing visual to keep responsive here.
        new Thread(() -> {
            String result;
            try {
                result = runArcAr(meshesDir);
            } catch (Throwable t) {
                result = "ArcAr run failed: " + t;
            }
            Log.i("ArcArNative", result);

            // This app is a headless "executable" wrapper, not an interactive
            // app, so we close ourselves once the native run is done.
            runOnUiThread(this::finish);
        }, "ArcAr-Thread").start();
    }
}
