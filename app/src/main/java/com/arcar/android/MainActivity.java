package com.arcar.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.opengl.GLSurfaceView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "ArcArUI";

    private GLSurfaceView glView;
    private GameRenderer renderer;
    private InputMapper input;
    private TextView hudText;
    private TextView hudCam;
    private TextView hudGoal;
    private View boostFill;
    private TextView consoleText;
    private ScrollView consoleScroll;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder consoleBuf = new StringBuilder();

    private volatile boolean engineReady = false;
    private volatile boolean controlsRunning = false;
    private boolean consoleVisible = false;

    // Keyboard/gamepad → InputMapper only (simulation binds)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        hudText = findViewById(R.id.hud_speed);
        hudCam = findViewById(R.id.hud_cam);
        boostFill = findViewById(R.id.boost_fill);
        hudGoal = findViewById(R.id.hud_goal);
        consoleText = findViewById(R.id.console_text);
        consoleScroll = findViewById(R.id.console_scroll);
        if (hudText != null) hudText.setText("Loading…");
        if (findViewById(R.id.hud_title) != null)
            ((TextView)findViewById(R.id.hud_title)).setText("ARCAR");
        log("UI up, starting native init on bg thread");

        glView = findViewById(R.id.gl_surface);
        glView.setEGLContextClientVersion(2);
        renderer = new GameRenderer();
        renderer.setContext(this);
        renderer.setEngineReady(false);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            if (engineReady) NativeBridge.nativeReset();
        });
        findViewById(R.id.btn_cam).setOnClickListener(v -> {
            if (engineReady) NativeBridge.nativeToggleBallCam();
        });
        findViewById(R.id.btn_console).setOnClickListener(v -> toggleConsole());

        renderer.setHudListener((boost, speed, ballCam, ready, boosting, goal) -> ui.post(() -> {
            if (!engineReady) return;
            if (hudText != null)
                hudText.setText(String.format(Locale.US, "%.0f uu/s%s", speed, boosting ? "  ·  BOOST" : ""));
            if (hudCam != null) {
                hudCam.setText(ballCam ? "BALL CAM" : "CAR CAM");
            }
            if (boostFill != null && boostFill.getParent() instanceof android.view.View) {
                android.view.View parent = (android.view.View) boostFill.getParent();
                int w = parent.getWidth();
                if (w > 0) {
                    android.view.ViewGroup.LayoutParams lp = boostFill.getLayoutParams();
                    lp.width = Math.max(1, (int)(w * Math.min(1f, boost / 100f)));
                    boostFill.setLayoutParams(lp);
                }
                int color = boost < 10 ? 0xFFFF4444 : (boosting ? 0xFFFFDD55 : 0xFFFFA020);
                boostFill.setBackgroundColor(color);
            }
            if (hudGoal != null) {
                if (goal) {
                    hudGoal.setVisibility(android.view.View.VISIBLE);
                    hudGoal.setText("GOAL!");
                } else if (hudGoal.getVisibility() == android.view.View.VISIBLE) {
                    // keep briefly — renderer drives flash; hide when not scoring
                    hudGoal.postDelayed(() -> hudGoal.setVisibility(android.view.View.GONE), 1200);
                }
            }
        }));

        input = new InputMapper(this);
        new Thread(this::initNativeEngine, "ArcAr-Init").start();
    }

    private void toggleConsole() {
        consoleVisible = !consoleVisible;
        consoleScroll.setVisibility(consoleVisible ? View.VISIBLE : View.GONE);
        if (consoleVisible) {
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                + "  " + msg + "\n";
        ui.post(() -> {
            consoleBuf.append(line);
            // keep last ~8KB
            if (consoleBuf.length() > 8000) {
                consoleBuf.delete(0, consoleBuf.length() - 6000);
            }
            if (consoleText != null) {
                consoleText.setText(consoleBuf.toString());
                if (consoleVisible) {
                    consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }

    private void initNativeEngine() {
        try {
            log("NativeBridge loaded=" + NativeBridge.isLoaded()
                    + (NativeBridge.isLoaded() ? "" : (" err=" + NativeBridge.getLoadError())));
            if (!NativeBridge.isLoaded()) {
                ui.post(() -> hudText.setText("ERROR: libarcar_jni.so missing"));
                return;
            }

            String meshesDir = getFilesDir().getAbsolutePath() + "/collision_meshes";
            log("nativeInit(meshes)…");
            boolean ok = NativeBridge.nativeInit(meshesDir);
            log("nativeInit(meshes) → " + ok);

            if (!ok) {
                log("nativeInit(\"\") fallback…");
                ok = NativeBridge.nativeInit("");
                log("nativeInit(\"\") → " + ok);
            }

            if (!ok) {
                ui.post(() -> hudText.setText("ERROR: native init failed — open Console"));
                log("INIT FAILED");
                return;
            }

            engineReady = true;
            renderer.setEngineReady(true);
            try {
                NativeBridge.nativeSetInfiniteBoost(input.isInfiniteBoost());
            } catch (Throwable ignored) {}
            ui.post(() -> {
                hudText.setText("ArcAr ready — drive!");
                startControlPump();
            });
            log("READY (infiniteBoost=" + input.isInfiniteBoost() + ")");
        } catch (Throwable t) {
            log("INIT CRASH: " + t);
            ui.post(() -> hudText.setText("ERROR: " + t.getClass().getSimpleName()));
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
            // Simulation only — camera / reset / console are UI buttons, not binds
            InputMapper.ControlsState c = input.poll();
            try {
                NativeBridge.nativeSetControls(
                        c.throttle, c.steer, c.pitch, c.yaw, c.roll,
                        c.jump, c.boost, c.handbrake);
                if (renderer != null) {
                    renderer.setSteerInput(c.steer);
                    renderer.setHandbrakeInput(c.handbrake ? 1f : 0f);
                }
            } catch (Throwable t) {
                log("controls: " + t);
            }
            ui.postDelayed(this, 8);
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // UI-only shortcuts (not simulation binds)
        if (keyCode == KeyEvent.KEYCODE_GRAVE || keyCode == KeyEvent.KEYCODE_F1) {
            toggleConsole();
            return true;
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
