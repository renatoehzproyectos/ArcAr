package com.arcar.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

/** Loads the Fennec GLB and (if imported) the current map GLB, on the GL thread. */
public class AssetStore {
    private static final String TAG = "AssetStore";
    public GlbModel car;
    public GlbModel map;
    private String loadedMapId; // MapCatalog.Entry.id currently loaded, so we can detect switches

    public void loadCar(Context ctx) {
        if (car != null) return;
        AssetManager am = ctx.getAssets();
        CarCatalog.Entry e = CarCatalog.car();
        car = GlbModel.load(am, e.assetPath, e.targetLengthUU);
        if (car != null) {
            car.uploadTextures();
            Log.i(TAG, "Fennec loaded, primitives=" + car.primitives.size() + " radius=" + car.radius);
        } else {
            Log.e(TAG, "Failed to load " + e.assetPath);
        }
    }

    /**
     * Loads the currently-selected imported map, if it's a .glb (raw .udk/.upk
     * maps have no renderable geometry yet). Safe to call every frame — it's a
     * no-op unless the selection changed. targetMaxExtent is passed as <= 0 so
     * the map keeps its native Unreal-unit world coordinates.
     *
     * showMap should be false whenever the session isn't MODE_IMPORTED (e.g.
     * baseplate) — otherwise a map selected in an earlier session lingers in
     * SharedPreferences and silently reappears under an unrelated mode.
     */
    public void loadMap(Context ctx, boolean showMap) {
        if (!showMap) {
            map = null;
            loadedMapId = null;
            return;
        }
        MapCatalog.Entry sel = MapCatalog.getSelected(ctx);
        String selId = sel != null ? sel.id : null;
        if (selId == null) {
            map = null;
            loadedMapId = null;
            return;
        }
        if (selId.equals(loadedMapId)) return; // already loaded (or already tried and failed)
        loadedMapId = selId;
        map = null;
        if (!sel.packageFile.toLowerCase().endsWith(".glb")) {
            Log.i(TAG, "Selected map has no extracted .glb yet: " + sel.packageFile);
            return;
        }
        java.io.File f = new java.io.File(new java.io.File(MapCatalog.mapsDir(ctx), sel.id), sel.packageFile);
        GlbModel m = GlbModel.load(f, -1f);
        if (m != null) {
            m.uploadTextures();
            map = m;
            Log.i(TAG, "Map loaded: " + sel.name + " primitives=" + m.primitives.size());
        } else {
            Log.e(TAG, "Failed to load map " + f);
        }
    }
}
