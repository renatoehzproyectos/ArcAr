package com.arcar.android;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Visual car bodies. Physics stays BODY_C; only mesh changes.
 * Models are CC-BY-4.0 (Sketchfab / Jako) — credit in About if shown.
 */
public final class CarCatalog {
    /**
     * Per-model visual orientation/offset correction (Fix #1 / #48 / #49).
     * The physics body's forward/right/up axes are always the source of truth;
     * this struct only corrects for how the *mesh* was authored so the rendered
     * nose lines up with physics-forward. Never used to alter physics.
     *
     * yawDeg/pitchDeg/rollDeg: rotation (degrees) applied to the model's local
     * axes so that local "nose" direction maps onto physics-forward (+Y model space
     * before this correction is assumed +X forward, +Y right, +Z up per glTF-ish convention
     * used by the importer — adjust per-model if the source mesh differs).
     */
    public static final class VisualTransform {
        public final float yawDeg, pitchDeg, rollDeg;
        public final float scale;
        public final float offsetX, offsetY, offsetZ;
        public VisualTransform(float yawDeg, float pitchDeg, float rollDeg, float scale,
                                float offsetX, float offsetY, float offsetZ) {
            this.yawDeg = yawDeg; this.pitchDeg = pitchDeg; this.rollDeg = rollDeg;
            this.scale = scale;
            this.offsetX = offsetX; this.offsetY = offsetY; this.offsetZ = offsetZ;
        }
    }

    public static final class Entry {
        public final String id;
        public final String displayName;
        public final String assetPath;
        public final float targetLengthUU;
        public final VisualTransform visual;
        public Entry(String id, String displayName, String assetPath, float targetLengthUU,
                     VisualTransform visual) {
            this.id = id;
            this.displayName = displayName;
            this.assetPath = assetPath;
            this.targetLengthUU = targetLengthUU;
            this.visual = visual;
        }
    }

    // Fix #1 final: GLB bounds show local +X is the long (nose) axis.
    // Physics basis already maps model +X -> physics forward, so correction = 0.
    // Never rotate the physics body to fix mesh orientation (Rule 1 / #34).
    public static final Entry[] CARS = {
            new Entry("octane", "Octane", "models/octane.glb", 120f,
                    new VisualTransform(0f, 0f, 0f, 1f, 0f, 0f, 0f)),
            new Entry("dominus", "Dominus", "models/dominus.glb", 128f,
                    new VisualTransform(0f, 0f, 0f, 1f, 0f, 0f, 0f)),
            new Entry("fennec", "Fennec", "models/fennec.glb", 122f,
                    new VisualTransform(0f, 0f, 0f, 1f, 0f, 0f, 0f)),
    };

    private static final String PREF = "arcar_car_v1";
    private static final String KEY = "car_id";

    public static String getSelectedId(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY, "octane");
    }

    public static void setSelectedId(Context ctx, String id) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, id).apply();
    }

    public static Entry find(String id) {
        for (Entry e : CARS) if (e.id.equals(id)) return e;
        return CARS[0];
    }

    public static int indexOf(String id) {
        for (int i = 0; i < CARS.length; i++) if (CARS[i].id.equals(id)) return i;
        return 0;
    }
}
