package com.arcar.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.opengl.GLSurfaceView;

/**
 * Playable ArcAr client.
 * Native init runs off the UI thread so the window is never frozen on the system splash.
 */
public class MainActivity extends Activity {

    private static final String TAG = "ArcArUI";

    private GLSurfaceView glView;
    private GameRenderer renderer;
    private InputMapper input;
    private TextView hudText;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean engineReady = false;
    private volatile boolean controlsRunning = false;

    private boolean keyW, keyS, keyA, keyD, keyUp, keyDown, keyLeft, keyRight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Draw UI immediately — never block here with native work
        setContentView(R.layout.activity_main);

        hudText = findViewById(R.id.hud);
        hudText.setText("Loading ArcAr…");

        glView = findViewById(R.id.gl_surface);
        glView.setEGLContextClientVersion(2);
        // Start with a no-op renderer until engine is ready (avoids native calls too early)
        renderer = new GameRenderer();
        renderer.setEngineReady(false);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        Button settingsBtn = findViewById(R.id.btn_settings);
        Button resetBtn = findViewById(R.id.btn_reset);
        Button camBtn = findViewById(R.id.btn_cam);

        settingsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        resetBtn.setOnClickListener(v -> {
            if (engineReady) NativeBridge.nativeReset();
        });
        camBtn.setOnClickListener(v -> {
            if (engineReady) NativeBridge.nativeToggleBallCam();
        });

        renderer.setHudListener((boost, speed, ballCam, ready) -> ui.post(() -> {
            if (!engineReady) return;
            String cam = ballCam ? "BALL CAM" : "CAR CAM";
            hudText.setText(String.format(
                    "Boost %d  |  Speed %.0f uu/s  |  %s",
                    Math.round(boost), speed, cam));
        }));

        input = new InputMapper(this);

        // Heavy native init OFF the main thread
        new Thread(this::initNativeEngine, "ArcAr-Init").start();
    }

    private void initNativeEngine() {
        try {
            // Touch NativeBridge here so loadLibrary runs on this thread, not UI
            String meshesDir = getFilesDir().getAbsolutePath() + "/collision_meshes";
            Log.i(TAG, "nativeInit start, meshes=" + meshesDir);

            boolean ok = NativeBridge.nativeInit(meshesDir);
            if (!ok) {
                Log.w(TAG, "Init with meshes failed, retry empty");
                ok = NativeBridge.nativeInit("");
            }

            if (!ok) {
                ui.post(() -> hudText.setText("ERROR: native init failed. Check logcat tag ArcArNative"));
                Log.e(TAG, "nativeInit failed both attempts");
                return;
            }

            engineReady = true;
            renderer.setEngineReady(true);
            ui.post(() -> {
                hudText.setText("ArcAr ready — drive!");
                startControlPump();
            });
            Log.i(TAG, "nativeInit OK");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
            ui.post(() -> hudText.setText("ERROR: native lib missing (" + e.getMessage() + ")"));
        } catch (Throwable t) {
            Log.e(TAG, "native init crash", t);
            ui.post(() -> hudText.setText("ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage()));
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

            float steer = 0, throttle = 0, pitch = 0, yaw = 0;
            if (keyA) steer -= 1;
            if (keyD) steer += 1;
            if (keyW) throttle += 1;
            if (keyS) throttle -= 1;
            if (keyUp) pitch += 1;
            if (keyDown) pitch -= 1;
            if (keyLeft) yaw -= 1;
            if (keyRight) yaw += 1;
            input.setKeyboardAxes(steer, throttle, pitch, yaw);

            InputMapper.ControlsState c = input.poll();
            try {
                NativeBridge.nativeSetControls(
                        c.throttle, c.steer, c.pitch, c.yaw, c.roll,
                        c.jump, c.boost, c.handbrake);
                if (c.ballCamPressed) NativeBridge.nativeToggleBallCam();
                if (c.resetPressed) NativeBridge.nativeReset();
            } catch (Throwable t) {
                Log.e(TAG, "controls error", t);
            }

            ui.postDelayed(this, 8);
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        updateKeyboardFlags(keyCode, true);
        if (input != null && input.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        updateKeyboardFlags(keyCode, false);
        if (input != null && input.onKeyUp(keyCode, event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private void updateKeyboardFlags(int keyCode, boolean down) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_W: keyW = down; break;
            case KeyEvent.KEYCODE_S: keyS = down; break;
            case KeyEvent.KEYCODE_A: keyA = down; break;
            case KeyEvent.KEYCODE_D: keyD = down; break;
            case KeyEvent.KEYCODE_DPAD_UP: keyUp = down; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: keyDown = down; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: keyLeft = down; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: keyRight = down; break;
        }
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
        if (engineReady) startControlPump();
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
        try {
            NativeBridge.nativeShutdown();
        } catch (Throwable ignored) {}
        super.onDestroy();
    }
}
