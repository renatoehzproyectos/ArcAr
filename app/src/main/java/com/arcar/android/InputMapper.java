package com.arcar.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Maps gamepad / keyboard input to CarControls + camera / reset.
 * Bindings stored in SharedPreferences; defaults match typical Xbox layout.
 */
public class InputMapper {

    public static final class ControlsState {
        public float throttle;   // -1..1 (from accel/reverse)
        public float steer;      // -1..1
        public float pitch;      // -1..1
        public float yaw;        // -1..1
        public float roll;       // -1..1
        public boolean jump;
        public boolean boost;
        public boolean handbrake;
        public boolean ballCamPressed;
        public boolean resetPressed;
    }

    private final SharedPreferences prefs;

    // Live axis / button state
    private float axisSteer, axisPitch, axisYaw;
    private float axisAccel, axisReverse; // triggers 0..1
    private boolean btnJump, btnBoost, btnSlide, btnRollL, btnRollR;
    private boolean btnBallCam, btnReset;
    private boolean ballCamEdge, resetEdge;
    private boolean prevBallCam, prevReset;

    // Default keycodes (Android KeyEvent)
    // A=96 JUMP, B=97 BOOST, X=99 POWERSLIDE, Y=100 BALL_CAM
    // LB=102 AIR_ROLL_L, RB=103 AIR_ROLL_R
    // SELECT/BACK=109 RESET (or button 4)
    private static final int DEF_JUMP = KeyEvent.KEYCODE_BUTTON_A;
    private static final int DEF_BOOST = KeyEvent.KEYCODE_BUTTON_B;
    private static final int DEF_SLIDE = KeyEvent.KEYCODE_BUTTON_X;
    private static final int DEF_BALLCAM = KeyEvent.KEYCODE_BUTTON_Y;
    private static final int DEF_ROLL_L = KeyEvent.KEYCODE_BUTTON_L1;
    private static final int DEF_ROLL_R = KeyEvent.KEYCODE_BUTTON_R1;
    private static final int DEF_RESET = KeyEvent.KEYCODE_BUTTON_SELECT;

    public InputMapper(Context ctx) {
        prefs = ctx.getSharedPreferences("arcar_bindings", Context.MODE_PRIVATE);
        ensureDefaults();
    }

    private void ensureDefaults() {
        SharedPreferences.Editor e = prefs.edit();
        putDefault(e, Action.JUMP, DEF_JUMP);
        putDefault(e, Action.BOOST, DEF_BOOST);
        putDefault(e, Action.POWERSLIDE, DEF_SLIDE);
        putDefault(e, Action.BALL_CAM, DEF_BALLCAM);
        putDefault(e, Action.AIR_ROLL_LEFT, DEF_ROLL_L);
        putDefault(e, Action.AIR_ROLL_RIGHT, DEF_ROLL_R);
        putDefault(e, Action.RESET, DEF_RESET);
        e.apply();
    }

    private void putDefault(SharedPreferences.Editor e, Action a, int keyCode) {
        String k = keyName(a);
        if (!prefs.contains(k)) e.putInt(k, keyCode);
    }

    private static String keyName(Action a) {
        return "bind_" + a.name();
    }

    public int getBinding(Action a) {
        return prefs.getInt(keyName(a), 0);
    }

    public void setBinding(Action a, int keyCode) {
        prefs.edit().putInt(keyName(a), keyCode).apply();
    }

    public void resetBindings() {
        prefs.edit().clear().apply();
        ensureDefaults();
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isGameDevice(event.getDevice())) {
            // Still allow keyboard fallback
        }
        return handleButton(keyCode, true);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleButton(keyCode, false);
    }

    private boolean handleButton(int keyCode, boolean down) {
        if (keyCode == getBinding(Action.JUMP)) { btnJump = down; return true; }
        if (keyCode == getBinding(Action.BOOST)) { btnBoost = down; return true; }
        if (keyCode == getBinding(Action.POWERSLIDE)) { btnSlide = down; return true; }
        if (keyCode == getBinding(Action.AIR_ROLL_LEFT)) { btnRollL = down; return true; }
        if (keyCode == getBinding(Action.AIR_ROLL_RIGHT)) { btnRollR = down; return true; }
        if (keyCode == getBinding(Action.BALL_CAM)) { btnBallCam = down; return true; }
        if (keyCode == getBinding(Action.RESET)) { btnReset = down; return true; }
        // Keyboard fallbacks
        if (keyCode == KeyEvent.KEYCODE_SPACE) { btnJump = down; return true; }
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) { btnBoost = down; return true; }
        if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT) { btnSlide = down; return true; }
        if (keyCode == KeyEvent.KEYCODE_Q) { btnRollL = down; return true; }
        if (keyCode == KeyEvent.KEYCODE_E) { btnRollR = down; return true; }
        if (keyCode == KeyEvent.KEYCODE_C) { btnBallCam = down; return true; }
        if (keyCode == KeyEvent.KEYCODE_R) { btnReset = down; return true; }
        return false;
    }

    public boolean onGenericMotion(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == 0
                && (event.getSource() & InputDevice.SOURCE_GAMEPAD) == 0) {
            return false;
        }
        if (event.getAction() != MotionEvent.ACTION_MOVE) return false;

        axisSteer = deadzone(event.getAxisValue(MotionEvent.AXIS_X));
        axisPitch = deadzone(-event.getAxisValue(MotionEvent.AXIS_Y)); // invert so up = nose up
        axisYaw = deadzone(event.getAxisValue(MotionEvent.AXIS_Z));
        // Some pads put yaw on RX
        float rx = deadzone(event.getAxisValue(MotionEvent.AXIS_RX));
        if (Math.abs(rx) > Math.abs(axisYaw)) axisYaw = rx;

        axisAccel = clamp01(event.getAxisValue(MotionEvent.AXIS_GAS));
        if (axisAccel == 0f) axisAccel = clamp01(event.getAxisValue(MotionEvent.AXIS_RTRIGGER));
        axisReverse = clamp01(event.getAxisValue(MotionEvent.AXIS_BRAKE));
        if (axisReverse == 0f) axisReverse = clamp01(event.getAxisValue(MotionEvent.AXIS_LTRIGGER));

        return true;
    }

    /** Also sample keyboard WASD each frame if desired */
    public void setKeyboardAxes(float steer, float throttle, float pitch, float yaw) {
        // Only override if non-zero so pad still works
        if (steer != 0f) axisSteer = steer;
        if (throttle > 0f) axisAccel = throttle;
        if (throttle < 0f) axisReverse = -throttle;
        if (pitch != 0f) axisPitch = pitch;
        if (yaw != 0f) axisYaw = yaw;
    }

    public ControlsState poll() {
        ControlsState s = new ControlsState();
        float th = axisAccel - axisReverse;
        // Keyboard W/S may set via setKeyboardAxes
        s.throttle = clamp(th, -1f, 1f);
        s.steer = clamp(axisSteer, -1f, 1f);
        s.pitch = clamp(axisPitch, -1f, 1f);
        s.yaw = clamp(axisYaw, -1f, 1f);

        float roll = 0f;
        if (btnRollL) roll -= 1f;
        if (btnRollR) roll += 1f;
        s.roll = roll;

        s.jump = btnJump;
        s.boost = btnBoost;
        s.handbrake = btnSlide;

        // Edge triggers for toggle actions
        ballCamEdge = btnBallCam && !prevBallCam;
        resetEdge = btnReset && !prevReset;
        prevBallCam = btnBallCam;
        prevReset = btnReset;
        s.ballCamPressed = ballCamEdge;
        s.resetPressed = resetEdge;
        return s;
    }

    private static boolean isGameDevice(InputDevice d) {
        if (d == null) return false;
        int sources = d.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    private static float deadzone(float v) {
        final float dz = 0.12f;
        if (Math.abs(v) < dz) return 0f;
        float sign = Math.signum(v);
        return sign * ((Math.abs(v) - dz) / (1f - dz));
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static String keyCodeLabel(int keyCode) {
        String name = KeyEvent.keyCodeToString(keyCode);
        if (name != null && name.startsWith("KEYCODE_")) {
            return name.substring("KEYCODE_".length());
        }
        return "KEY_" + keyCode;
    }
}
