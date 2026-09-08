package com.arcar.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Maps gamepad / keyboard → CarControls only (simulation).
 * Each Action supports up to TWO key bindings (primary + secondary).
 * Camera / reset / console are NOT handled here.
 */
public class InputMapper {

    public static final int SLOT_PRIMARY = 0;
    public static final int SLOT_SECONDARY = 1;
    public static final int UNBOUND = 0;

    public static final class ControlsState {
        public float throttle;   // -1..1
        public float steer;      // -1..1
        public float pitch;      // -1..1
        public float yaw;        // -1..1
        public float roll;       // -1..1
        public boolean jump;
        public boolean boost;
        public boolean handbrake;
    }

    private final SharedPreferences prefs;

    // Analog axes (sticks / triggers) — always available alongside digital binds
    private float axisSteer, axisPitch, axisYaw;
    private float axisAccel, axisReverse; // triggers 0..1

    // Digital held state for each sim action
    private final boolean[] held = new boolean[Action.values().length];

    // Keyboard axis overlay from WASD-style flags (optional, from MainActivity)
    private float kbSteer, kbThrottle, kbPitch, kbYaw;

    public InputMapper(Context ctx) {
        prefs = ctx.getSharedPreferences("arcar_bindings_v2", Context.MODE_PRIVATE);
        ensureDefaults();
    }

    private void ensureDefaults() {
        SharedPreferences.Editor e = prefs.edit();
        // Primary defaults (Xbox-style + keyboard)
        putDefault(e, Action.JUMP, SLOT_PRIMARY, KeyEvent.KEYCODE_BUTTON_A);
        putDefault(e, Action.JUMP, SLOT_SECONDARY, KeyEvent.KEYCODE_SPACE);

        putDefault(e, Action.BOOST, SLOT_PRIMARY, KeyEvent.KEYCODE_BUTTON_B);
        putDefault(e, Action.BOOST, SLOT_SECONDARY, KeyEvent.KEYCODE_SHIFT_LEFT);

        putDefault(e, Action.POWERSLIDE, SLOT_PRIMARY, KeyEvent.KEYCODE_BUTTON_X);
        putDefault(e, Action.POWERSLIDE, SLOT_SECONDARY, KeyEvent.KEYCODE_CTRL_LEFT);

        putDefault(e, Action.AIR_ROLL_LEFT, SLOT_PRIMARY, KeyEvent.KEYCODE_BUTTON_L1);
        putDefault(e, Action.AIR_ROLL_LEFT, SLOT_SECONDARY, KeyEvent.KEYCODE_Q);

        putDefault(e, Action.AIR_ROLL_RIGHT, SLOT_PRIMARY, KeyEvent.KEYCODE_BUTTON_R1);
        putDefault(e, Action.AIR_ROLL_RIGHT, SLOT_SECONDARY, KeyEvent.KEYCODE_E);

        putDefault(e, Action.ACCELERATE, SLOT_PRIMARY, KeyEvent.KEYCODE_W);
        putDefault(e, Action.ACCELERATE, SLOT_SECONDARY, KeyEvent.KEYCODE_DPAD_UP);

        putDefault(e, Action.DECELERATE, SLOT_PRIMARY, KeyEvent.KEYCODE_S);
        putDefault(e, Action.DECELERATE, SLOT_SECONDARY, KeyEvent.KEYCODE_DPAD_DOWN);

        putDefault(e, Action.STEER_LEFT, SLOT_PRIMARY, KeyEvent.KEYCODE_A);
        putDefault(e, Action.STEER_LEFT, SLOT_SECONDARY, KeyEvent.KEYCODE_DPAD_LEFT);

        putDefault(e, Action.STEER_RIGHT, SLOT_PRIMARY, KeyEvent.KEYCODE_D);
        putDefault(e, Action.STEER_RIGHT, SLOT_SECONDARY, KeyEvent.KEYCODE_DPAD_RIGHT);

        putDefault(e, Action.PITCH_UP, SLOT_PRIMARY, UNBOUND);
        putDefault(e, Action.PITCH_DOWN, SLOT_PRIMARY, UNBOUND);
        putDefault(e, Action.YAW_LEFT, SLOT_PRIMARY, UNBOUND);
        putDefault(e, Action.YAW_RIGHT, SLOT_PRIMARY, UNBOUND);

        e.apply();
    }

    private void putDefault(SharedPreferences.Editor e, Action a, int slot, int keyCode) {
        String k = keyName(a, slot);
        if (!prefs.contains(k)) e.putInt(k, keyCode);
    }

    private static String keyName(Action a, int slot) {
        return "bind_" + a.name() + "_" + slot;
    }

    /** Get binding for slot 0 or 1. 0 = unbound. */
    public int getBinding(Action a, int slot) {
        return prefs.getInt(keyName(a, slot), UNBOUND);
    }

    /** @deprecated use getBinding(action, slot) */
    public int getBinding(Action a) {
        return getBinding(a, SLOT_PRIMARY);
    }

    /**
     * Set one slot without clearing the other.
     * If keyCode is already used by another action, that other slot is cleared.
     */
    public void setBinding(Action a, int slot, int keyCode) {
        SharedPreferences.Editor e = prefs.edit();
        if (keyCode != UNBOUND) {
            // Remove this key from any other action/slot so it only maps once
            for (Action other : Action.values()) {
                for (int s = 0; s <= 1; s++) {
                    if (other == a && s == slot) continue;
                    if (prefs.getInt(keyName(other, s), UNBOUND) == keyCode) {
                        e.putInt(keyName(other, s), UNBOUND);
                    }
                }
            }
        }
        e.putInt(keyName(a, slot), keyCode);
        e.apply();
    }

    /** Replace primary only (legacy). */
    public void setBinding(Action a, int keyCode) {
        setBinding(a, SLOT_PRIMARY, keyCode);
    }

    public void clearBinding(Action a, int slot) {
        setBinding(a, slot, UNBOUND);
    }

    public void resetBindings() {
        prefs.edit().clear().apply();
        ensureDefaults();
        // clear held state
        for (int i = 0; i < held.length; i++) held[i] = false;
    }

    /** Human-readable label for both slots, e.g. "W / DPAD_UP" */
    public String formatBindings(Action a) {
        int p = getBinding(a, SLOT_PRIMARY);
        int s = getBinding(a, SLOT_SECONDARY);
        if (p == UNBOUND && s == UNBOUND) return "(none)";
        if (s == UNBOUND) return keyCodeLabel(p);
        if (p == UNBOUND) return keyCodeLabel(s);
        return keyCodeLabel(p) + "  /  " + keyCodeLabel(s);
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleButton(keyCode, true);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleButton(keyCode, false);
    }

    private boolean handleButton(int keyCode, boolean down) {
        boolean matched = false;
        for (Action a : Action.values()) {
            int p = getBinding(a, SLOT_PRIMARY);
            int s = getBinding(a, SLOT_SECONDARY);
            if (keyCode == p || keyCode == s) {
                // Held if either bound key is still conceptually down — we track OR of slots
                // Simple approach: set held from this key event; for multi-key need per-slot
                setHeldFromKey(a, keyCode, down);
                matched = true;
            }
        }
        return matched;
    }

    // Per-slot hold tracking so releasing one key doesn't clear if the other is still down
    private final boolean[] heldPrimary = new boolean[Action.values().length];
    private final boolean[] heldSecondary = new boolean[Action.values().length];

    private void setHeldFromKey(Action a, int keyCode, boolean down) {
        int idx = a.ordinal();
        int p = getBinding(a, SLOT_PRIMARY);
        int s = getBinding(a, SLOT_SECONDARY);
        if (keyCode == p) heldPrimary[idx] = down;
        if (keyCode == s) heldSecondary[idx] = down;
        held[idx] = heldPrimary[idx] || heldSecondary[idx];
    }

    public boolean onGenericMotion(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == 0
                && (event.getSource() & InputDevice.SOURCE_GAMEPAD) == 0) {
            return false;
        }
        if (event.getAction() != MotionEvent.ACTION_MOVE) return false;

        axisSteer = deadzone(event.getAxisValue(MotionEvent.AXIS_X));
        axisPitch = deadzone(-event.getAxisValue(MotionEvent.AXIS_Y));
        axisYaw = deadzone(event.getAxisValue(MotionEvent.AXIS_Z));
        float rx = deadzone(event.getAxisValue(MotionEvent.AXIS_RX));
        if (Math.abs(rx) > Math.abs(axisYaw)) axisYaw = rx;

        // RT = accelerate, LT = decelerate (always, independent of button binds)
        axisAccel = clamp01(event.getAxisValue(MotionEvent.AXIS_GAS));
        if (axisAccel == 0f) axisAccel = clamp01(event.getAxisValue(MotionEvent.AXIS_RTRIGGER));
        axisReverse = clamp01(event.getAxisValue(MotionEvent.AXIS_BRAKE));
        if (axisReverse == 0f) axisReverse = clamp01(event.getAxisValue(MotionEvent.AXIS_LTRIGGER));

        return true;
    }

    /** Optional keyboard axis overlay from MainActivity (legacy WASD). Prefer binds instead. */
    public void setKeyboardAxes(float steer, float throttle, float pitch, float yaw) {
        kbSteer = steer;
        kbThrottle = throttle;
        kbPitch = pitch;
        kbYaw = yaw;
    }

    public ControlsState poll() {
        ControlsState st = new ControlsState();

        // Throttle: triggers + digital ACCELERATE / DECELERATE
        float th = axisAccel - axisReverse;
        if (isHeld(Action.ACCELERATE)) th = Math.max(th, 1f);
        if (isHeld(Action.DECELERATE)) th = Math.min(th, -1f);
        if (kbThrottle != 0f) th = kbThrottle;
        st.throttle = clamp(th, -1f, 1f);

        // Steer: stick + digital
        float steer = axisSteer;
        if (isHeld(Action.STEER_LEFT)) steer = Math.min(steer, -1f);
        if (isHeld(Action.STEER_RIGHT)) steer = Math.max(steer, 1f);
        if (kbSteer != 0f) steer = kbSteer;
        st.steer = clamp(steer, -1f, 1f);

        float pitch = axisPitch;
        if (isHeld(Action.PITCH_UP)) pitch = Math.max(pitch, 1f);
        if (isHeld(Action.PITCH_DOWN)) pitch = Math.min(pitch, -1f);
        if (kbPitch != 0f) pitch = kbPitch;
        st.pitch = clamp(pitch, -1f, 1f);

        float yaw = axisYaw;
        if (isHeld(Action.YAW_LEFT)) yaw = Math.min(yaw, -1f);
        if (isHeld(Action.YAW_RIGHT)) yaw = Math.max(yaw, 1f);
        if (kbYaw != 0f) yaw = kbYaw;
        st.yaw = clamp(yaw, -1f, 1f);

        float roll = 0f;
        if (isHeld(Action.AIR_ROLL_LEFT)) roll -= 1f;
        if (isHeld(Action.AIR_ROLL_RIGHT)) roll += 1f;
        st.roll = roll;

        st.jump = isHeld(Action.JUMP);
        st.boost = isHeld(Action.BOOST);
        st.handbrake = isHeld(Action.POWERSLIDE);
        return st;
    }

    private boolean isHeld(Action a) {
        return held[a.ordinal()];
    }

    public static String keyCodeLabel(int keyCode) {
        if (keyCode == UNBOUND) return "—";
        String name = KeyEvent.keyCodeToString(keyCode);
        if (name != null && name.startsWith("KEYCODE_")) {
            return name.substring("KEYCODE_".length());
        }
        return "KEY_" + keyCode;
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
}
