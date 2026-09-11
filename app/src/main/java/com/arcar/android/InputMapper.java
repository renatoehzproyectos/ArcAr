package com.arcar.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Rocket League-style mapping:
 * - Sticks: steer / pitch / yaw (analogue only)
 * - Triggers: accelerate / decelerate (also bindable as digital)
 * - Buttons: jump, boost, powerslide, air roll L/R
 *
 * One physical key can be bound to MANY actions at once
 * (e.g. L2 = decelerate AND air roll left).
 */
public class InputMapper {

    public static final int UNBOUND = 0;

    public static final class ControlsState {
        public float throttle;
        public float steer;
        public float pitch;
        public float yaw;
        public float roll;
        public boolean jump;
        public boolean boost;
        public boolean handbrake;
    }

    private final SharedPreferences prefs;

    // Analogue
    private float axisSteer, axisPitch, axisYaw;
    private float axisAccel, axisReverse;

    // Digital held per action (OR of all keys bound to that action)
    private final boolean[] held = new boolean[Action.values().length];
    // Which keycodes are currently down
    private final android.util.SparseBooleanArray keysDown = new android.util.SparseBooleanArray();

    private boolean infiniteBoost = false;

    public InputMapper(Context ctx) {
        prefs = ctx.getSharedPreferences("arcar_bindings_v4", Context.MODE_PRIVATE);
        ensureDefaults();
        infiniteBoost = prefs.getBoolean("infinite_boost", false);
    }

    private void ensureDefaults() {
        SharedPreferences.Editor e = prefs.edit();
        // Defaults = captura CONTROLES (solo mando, sin teclado).
        // Una misma tecla puede ir a varias acciones (L1/L2 compartidos).
        // Face: Cross(A) se muestra "X", Square(X) se muestra "□".
        putDefault(e, Action.JUMP, KeyEvent.KEYCODE_BUTTON_R1);          // R1
        putDefault(e, Action.BOOST, KeyEvent.KEYCODE_BUTTON_A);          // X (Cross)
        putDefault(e, Action.ACCELERATE, KeyEvent.KEYCODE_BUTTON_R2);    // R2
        putDefault(e, Action.DECELERATE, KeyEvent.KEYCODE_BUTTON_L2);    // L2
        putDefault(e, Action.POWERSLIDE, KeyEvent.KEYCODE_BUTTON_L1);    // L1
        putDefault(e, Action.TOGGLE_BALL_CAM, KeyEvent.KEYCODE_BUTTON_X); // □ (Square)
        putDefault(e, Action.AIR_ROLL_RIGHT, KeyEvent.KEYCODE_BUTTON_L1); // L1
        putDefault(e, Action.AIR_ROLL_LEFT, KeyEvent.KEYCODE_BUTTON_L2);  // L2
        if (!prefs.contains("infinite_boost")) e.putBoolean("infinite_boost", false);
        e.apply();
    }

    private void putDefault(SharedPreferences.Editor e, Action a, int keyCode) {
        String k = keysPref(a);
        if (!prefs.contains(k)) e.putString(k, String.valueOf(keyCode));
    }

    private void putDefaultExtra(SharedPreferences.Editor e, Action a, int keyCode) {
        String k = keysPref(a);
        if (!prefs.contains(k)) return; // primary will set it
        // Only add extra if still at single default from putDefault in same batch — handled after apply
        // Simpler: append if not present when loading first time via flag
        String flag = "seeded_" + a.name();
        if (!prefs.contains(flag)) {
            String cur = prefs.getString(k, "");
            // will be fixed in seedExtras
        }
    }

    private void seedExtrasOnce() {
        // No keyboard secondary binds — defaults are gamepad-only (see ensureDefaults).
        if (prefs.getBoolean("seeded_extras_v4", false)) return;
        prefs.edit().putBoolean("seeded_extras_v4", true).apply();
    }

    private static String keysPref(Action a) {
        return "keys_" + a.name();
    }

    /** All keycodes bound to this action (comma-separated in prefs). */
    public int[] getKeycodes(Action a) {
        seedExtrasOnce();
        String raw = prefs.getString(keysPref(a), "");
        if (raw == null || raw.isEmpty()) return new int[0];
        String[] parts = raw.split(",");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            p = p.trim();
            if (p.isEmpty()) continue;
            try {
                int v = Integer.parseInt(p);
                if (v != UNBOUND) out[n++] = v;
            } catch (NumberFormatException ignored) {}
        }
        if (n == out.length) return out;
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private void saveKeycodes(Action a, int[] codes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == UNBOUND) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(codes[i]);
        }
        prefs.edit().putString(keysPref(a), sb.toString()).apply();
    }

    /** Add a key to this action without removing it from other actions. */
    public void addBinding(Action a, int keyCode) {
        if (keyCode == UNBOUND) return;
        int[] cur = getKeycodes(a);
        for (int c : cur) if (c == keyCode) return; // already
        int[] next = new int[cur.length + 1];
        System.arraycopy(cur, 0, next, 0, cur.length);
        next[cur.length] = keyCode;
        saveKeycodes(a, next);
    }

    private void appendKey(SharedPreferences.Editor e, Action a, int keyCode) {
        String k = keysPref(a);
        String cur = prefs.getString(k, e == null ? "" : null);
        // read from prefs after primary defaults applied
        cur = prefs.getString(k, "");
        if (cur == null) cur = "";
        if (cur.contains(String.valueOf(keyCode))) return;
        if (cur.isEmpty()) cur = String.valueOf(keyCode);
        else cur = cur + "," + keyCode;
        e.putString(k, cur);
    }

    /** Remove one key from this action only. */
    public void removeBinding(Action a, int keyCode) {
        int[] cur = getKeycodes(a);
        int n = 0;
        int[] tmp = new int[cur.length];
        for (int c : cur) if (c != keyCode) tmp[n++] = c;
        int[] next = new int[n];
        System.arraycopy(tmp, 0, next, 0, n);
        saveKeycodes(a, next);
    }

    /** Clear all keys for this action. */
    public void clearBindings(Action a) {
        saveKeycodes(a, new int[0]);
    }

    public void resetBindings() {
        prefs.edit().clear().apply();
        ensureDefaults();
        seedExtrasOnce();
        for (int i = 0; i < held.length; i++) held[i] = false;
        keysDown.clear();
        infiniteBoost = false;
    }

    public boolean isInfiniteBoost() {
        return infiniteBoost;
    }

    public void setInfiniteBoost(boolean on) {
        infiniteBoost = on;
        prefs.edit().putBoolean("infinite_boost", on).apply();
    }

    public String formatBindings(Action a) {
        int[] codes = getKeycodes(a);
        if (codes.length == 0) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codes.length; i++) {
            if (i > 0) sb.append("  +  ");
            sb.append(keyCodeLabel(codes[i]));
        }
        return sb.toString();
    }

    /** True on the frame an action becomes held (edge). Call from UI thread after poll. */
    private final boolean[] prevHeld = new boolean[Action.values().length];

    public boolean consumePress(Action a) {
        boolean now = held[a.ordinal()];
        boolean edge = now && !prevHeld[a.ordinal()];
        prevHeld[a.ordinal()] = now;
        return edge;
    }

    public void syncPrevHeld() {
        for (int i = 0; i < held.length; i++) prevHeld[i] = held[i];
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        keysDown.put(keyCode, true);
        recomputeHeld();
        return isBoundKey(keyCode);
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        keysDown.put(keyCode, false);
        recomputeHeld();
        return isBoundKey(keyCode);
    }

    private boolean isBoundKey(int keyCode) {
        for (Action a : Action.values()) {
            for (int c : getKeycodes(a)) {
                if (c == keyCode) return true;
            }
        }
        return false;
    }

    private void recomputeHeld() {
        for (Action a : Action.values()) {
            boolean h = false;
            for (int c : getKeycodes(a)) {
                if (keysDown.get(c, false)) {
                    h = true;
                    break;
                }
            }
            held[a.ordinal()] = h;
        }
    }

    public boolean onGenericMotion(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == 0
                && (event.getSource() & InputDevice.SOURCE_GAMEPAD) == 0) {
            return false;
        }
        if (event.getAction() != MotionEvent.ACTION_MOVE) return false;

        // Analogue only — like Rocket League
        // Match physical stick directions (Android Y is often up=-1, so leave as-is for pitch-up)
        axisSteer = deadzone(-event.getAxisValue(MotionEvent.AXIS_X)); // left = steer left
        axisPitch = deadzone(event.getAxisValue(MotionEvent.AXIS_Y));  // stick up = pitch up
        axisYaw = deadzone(-event.getAxisValue(MotionEvent.AXIS_Z));
        float rx = deadzone(-event.getAxisValue(MotionEvent.AXIS_RX));
        if (Math.abs(rx) > Math.abs(axisYaw)) axisYaw = rx;
        // Right stick Y unused for pitch (RL uses left stick for pitch)

        axisAccel = clamp01(event.getAxisValue(MotionEvent.AXIS_GAS));
        if (axisAccel == 0f) axisAccel = clamp01(event.getAxisValue(MotionEvent.AXIS_RTRIGGER));
        axisReverse = clamp01(event.getAxisValue(MotionEvent.AXIS_BRAKE));
        if (axisReverse == 0f) axisReverse = clamp01(event.getAxisValue(MotionEvent.AXIS_LTRIGGER));

        return true;
    }

    public ControlsState poll() {
        ControlsState st = new ControlsState();

        float th = axisAccel - axisReverse;
        if (held[Action.ACCELERATE.ordinal()]) th = Math.max(th, 1f);
        if (held[Action.DECELERATE.ordinal()]) th = Math.min(th, -1f);
        st.throttle = clamp(th, -1f, 1f);

        // Rocket League: left stick X = steer on ground AND yaw in air
        st.steer = clamp(axisSteer, -1f, 1f);
        st.pitch = clamp(axisPitch, -1f, 1f);
        // Yaw: left stick X (primary) + right stick (optional add)
        float yaw = axisSteer + axisYaw;
        st.yaw = clamp(yaw, -1f, 1f);

        float roll = 0f;
        if (held[Action.AIR_ROLL_LEFT.ordinal()]) roll -= 1f;
        if (held[Action.AIR_ROLL_RIGHT.ordinal()]) roll += 1f;
        st.roll = roll;

        st.jump = held[Action.JUMP.ordinal()];
        st.boost = held[Action.BOOST.ordinal()]; // infinite boost is fuel-only (native refill)
        st.handbrake = held[Action.POWERSLIDE.ordinal()];
        return st;
    }

    public static String keyCodeLabel(int keyCode) {
        if (keyCode == UNBOUND) return "—";
        switch (keyCode) {
            // PlayStation-style face labels (match settings UI screenshot)
            case KeyEvent.KEYCODE_BUTTON_A: return "X";   // Cross
            case KeyEvent.KEYCODE_BUTTON_B: return "○";   // Circle
            case KeyEvent.KEYCODE_BUTTON_X: return "□";   // Square
            case KeyEvent.KEYCODE_BUTTON_Y: return "△";   // Triangle
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            case KeyEvent.KEYCODE_DPAD_UP: return "DPAD ↑";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DPAD ↓";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "DPAD ←";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "DPAD →";
            default: break;
        }
        String name = KeyEvent.keyCodeToString(keyCode);
        if (name != null && name.startsWith("KEYCODE_")) {
            return name.substring("KEYCODE_".length()).replace('_', ' ');
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
