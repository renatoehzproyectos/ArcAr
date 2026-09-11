package com.arcar.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

/** Loads the Fennec GLB on the GL thread. */
public class AssetStore {
    private static final String TAG = "AssetStore";
    public GlbModel car;

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
}
