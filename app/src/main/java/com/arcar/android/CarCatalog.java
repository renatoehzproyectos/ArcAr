package com.arcar.android;

/**
 * Visual car body only. Physics stays BODY_C.
 * Fennec GLB: local +X is the long (nose) axis; maps to physics forward.
 */
public final class CarCatalog {
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
        public final String assetPath;
        public final float targetLengthUU;
        public final VisualTransform visual;
        public Entry(String id, String assetPath, float targetLengthUU, VisualTransform visual) {
            this.id = id;
            this.assetPath = assetPath;
            this.targetLengthUU = targetLengthUU;
            this.visual = visual;
        }
    }

    // BODY_C hitbox length ~131 UU; target slightly under so mesh fits hitbox silhouette
    public static final Entry FENNEC = new Entry(
            "fennec", "models/fennec.glb", 122f,
            new VisualTransform(0f, 0f, 0f, 1f, 0f, 0f, 0f));

    public static Entry car() { return FENNEC; }
}
