package com.arcar.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Loads GLB cars/ball + Kenney particle textures on the GL thread. */
public class AssetStore {
    private static final String TAG = "AssetStore";

    public GlbModel car;
    public GlbModel ball;
    public final Map<String, Integer> particleTex = new HashMap<>();

    private String loadedCarId = "";

    public void loadAll(Context ctx, String carId) {
        AssetManager am = ctx.getAssets();
        CarCatalog.Entry e = CarCatalog.find(carId);
        if (!e.id.equals(loadedCarId) || car == null) {
            car = GlbModel.load(am, e.assetPath, e.targetLengthUU);
            if (car != null) car.uploadTextures();
            loadedCarId = e.id;
            Log.i(TAG, "Car loaded: " + e.id);
        }
        if (ball == null) {
            // RL ball radius ~91.25 UU diameter ~182
            ball = GlbModel.load(am, "models/ball.glb", 182.5f);
            if (ball != null) ball.uploadTextures();
            Log.i(TAG, "Ball loaded");
        }
        String[] particles = {
                "textures/particles/flame_01.png",
                "textures/particles/flame_03.png",
                "textures/particles/flame_05.png",
                "textures/particles/fire_01.png",
                "textures/particles/flare_01.png",
                "textures/particles/muzzle_01.png",
                "textures/particles/spark_01.png",
                "textures/particles/circle_01.png",
                "textures/smoke/blackSmoke00.png",
                "textures/smoke/blackSmoke05.png",
                "textures/smoke/explosion00.png",
        };
        for (String path : particles) {
            if (particleTex.containsKey(path)) continue;
            int id = loadTexture(am, path);
            if (id != 0) particleTex.put(path, id);
        }
    }

    public int tex(String path) {
        Integer id = particleTex.get(path);
        return id != null ? id : 0;
    }

    private static int loadTexture(AssetManager am, String path) {
        try (InputStream in = am.open(path)) {
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) return 0;
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            bmp.recycle();
            return tex[0];
        } catch (Exception e) {
            Log.w(TAG, "tex fail " + path + ": " + e.getMessage());
            return 0;
        }
    }
}
