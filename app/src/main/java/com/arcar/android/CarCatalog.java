package com.arcar.android;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Visual car bodies. Physics stays BODY_C; only mesh changes.
 * Models are CC-BY-4.0 (Sketchfab / Jako) — credit in About if shown.
 */
public final class CarCatalog {
    public static final class Entry {
        public final String id;
        public final String displayName;
        public final String assetPath;
        public final float targetLengthUU;
        public Entry(String id, String displayName, String assetPath, float targetLengthUU) {
            this.id = id;
            this.displayName = displayName;
            this.assetPath = assetPath;
            this.targetLengthUU = targetLengthUU;
        }
    }

    public static final Entry[] CARS = {
            new Entry("octane", "Octane", "models/octane.glb", 120f),
            new Entry("dominus", "Dominus", "models/dominus.glb", 128f),
            new Entry("fennec", "Fennec", "models/fennec.glb", 122f),
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
