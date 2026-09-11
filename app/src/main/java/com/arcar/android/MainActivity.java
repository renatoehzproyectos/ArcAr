package com.arcar.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.opengl.GLSurfaceView;

/**
 * ArcAr Alpha 0.1 — minimal bootstrap.
 * GLSurfaceView → GameRenderer → GameEngine → Input.
 * No HUD, menus, settings, console or overlays.
 */
public class MainActivity extends Activity {

    private static final String TAG = "ArcAr";

    private GLSurfaceView glView;
    private GameRenderer renderer;
    private InputMapper input;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean engineReady = false;
    private volatile boolean controlsRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        glView = findViewById(R.id.gl_surface);
        glView.setEGLContextClientVersion(2);
        renderer = new GameRenderer();
        renderer.setEngineReady(false);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        input = new InputMapper(this);
        new Thread(this::initNativeEngine, "ArcAr-Init").start();
    }

    private void initNativeEngine() {
        try {
            if (!NativeBridge.isLoaded()) {
                Log.e(TAG, "libarcar_jni.so missing: " + NativeBridge.getLoadError());
                return;
            }

            // Empty meshes dir → plane-only arena (no stadium meshes)
            boolean ok = NativeBridge.nativeInit("");
            if (!ok) {
                Log.e(TAG, "nativeInit failed");
                return;
            }

            engineReady = true;
            renderer.setEngineReady(true);
            try {
                NativeBridge.nativeSetInfiniteBoost(input.isInfiniteBoost());
            } catch (Throwable ignored) {}
            ui.post(this::startControlPump);
            Log.i(TAG, "READY — Alpha 0.1 gameplay core");
        } catch (Throwable t) {
            Log.e(TAG, "INIT CRASH", t);
        }
    }

    private void startControlPump() {
        if (controlsRunning) return;
        controlsRunning = true;
        ui.post(controlPump);
    }

    private final Runnable controlPump = new Runnable() {
        @Override
        public void run() {
            if (!engineReady) return;
            InputMapper.ControlsState c = input.poll();
            try {
                NativeBridge.nativeSetControls(
                        c.throttle, c.steer, c.pitch, c.yaw, c.roll,
                        c.jump, c.boost, c.handbrake);
            } catch (Throwable t) {
                Log.w(TAG, "controls: " + t);
            }
            ui.postDelayed(this, 8);
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (engineReady) {
            if (keyCode == KeyEvent.KEYCODE_C && event.getRepeatCount() == 0) {
                NativeBridge.nativeToggleBallCam();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_R && event.getRepeatCount() == 0) {
                NativeBridge.nativeReset();
                return true;
            }
        }
        if (input != null && input.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (input != null && input.onKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (input != null && input.onGenericMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
        input = new InputMapper(this);
        if (engineReady) {
            try {
                NativeBridge.nativeSetInfiniteBoost(input.isInfiniteBoost());
            } catch (Throwable ignored) {}
            startControlPump();
        }
    }

    @Override
    protected void onPause() {
        if (glView != null) glView.onPause();
        controlsRunning = false;
        ui.removeCallbacks(controlPump);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(controlPump);
        controlsRunning = false;
        engineReady = false;
        try { NativeBridge.nativeShutdown(); } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
