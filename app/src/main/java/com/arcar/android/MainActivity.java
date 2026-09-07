package com.arcar.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.opengl.GLSurfaceView;

/**
 * Playable ArcAr client: fullscreen GL view + controller/keyboard input + HUD.
 */
public class MainActivity extends Activity {

    private GLSurfaceView glView;
    private GameRenderer renderer;
    private InputMapper input;
    private TextView hudText;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float[] snap = new float[30];

    // Keyboard held keys for axes
    private boolean keyW, keyS, keyA, keyD, keyUp, keyDown, keyLeft, keyRight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        input = new InputMapper(this);

        String meshesDir = getFilesDir().getAbsolutePath() + "/collision_meshes";
        boolean ok = NativeBridge.nativeInit(meshesDir);
        if (!ok) {
            // Still try — engine may work without meshes
            NativeBridge.nativeInit("");
        }

        FrameLayout root = findViewById(R.id.root);
        glView = findViewById(R.id.gl_surface);
        glView.setEGLContextClientVersion(2);
        renderer = new GameRenderer();
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        hudText = findViewById(R.id.hud);
        Button settingsBtn = findViewById(R.id.btn_settings);
        Button resetBtn = findViewById(R.id.btn_reset);
        Button camBtn = findViewById(R.id.btn_cam);

        settingsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        resetBtn.setOnClickListener(v -> NativeBridge.nativeReset());
        camBtn.setOnClickListener(v -> NativeBridge.nativeToggleBallCam());

        renderer.setHudListener((boost, speed, ballCam, ready) -> ui.post(() -> {
            String cam = ballCam ? "BALL CAM" : "CAR CAM";
            hudText.setText(String.format(
                    "Boost %d  |  Speed %.0f uu/s  |  %s%s",
                    Math.round(boost), speed, cam, ready ? "" : "  (loading…)"));
        }));

        // Feed controls every frame from UI thread clock as backup
        ui.post(controlPump);
    }

    private final Runnable controlPump = new Runnable() {
        @Override
        public void run() {
            // Keyboard axes
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
            NativeBridge.nativeSetControls(
                    c.throttle, c.steer, c.pitch, c.yaw, c.roll,
                    c.jump, c.boost, c.handbrake);
            if (c.ballCamPressed) NativeBridge.nativeToggleBallCam();
            if (c.resetPressed) NativeBridge.nativeReset();

            ui.postDelayed(this, 8); // ~120 Hz control sample
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        updateKeyboardFlags(keyCode, true);
        if (input.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        updateKeyboardFlags(keyCode, false);
        if (input.onKeyUp(keyCode, event)) return true;
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
        if (input.onGenericMotion(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glView != null) glView.onResume();
        // Reload bindings in case user changed them in settings
        input = new InputMapper(this);
    }

    @Override
    protected void onPause() {
        if (glView != null) glView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(controlPump);
        NativeBridge.nativeShutdown();
        super.onDestroy();
    }
}
