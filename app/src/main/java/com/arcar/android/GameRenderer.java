package com.arcar.android;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Alpha 0.1 renderer + Fennec GLB car.
 * Ball = sphere, ground = plane. No stadium/VFX/HUD.
 */
public class GameRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "GameRenderer";

    private final float[] snapshot = new float[40];
    private final Object snapLock = new Object();

    private final float[] view = new float[16];
    private final float[] proj = new float[16];
    private final float[] mvp = new float[16];
    private final float[] model = new float[16];
    private final float[] tmp = new float[16];
    private final float[] tmp2 = new float[16];
    private final float[] corrM = new float[16];
    private final float[] offsetM = new float[16];
    private final float[] orientedM = new float[16];
    private final float[] lightDir = new float[]{0.4f, 0.25f, 0.85f};
    private final float[] currentCameraPos = new float[3];

    private FloatBuffer cubePN;
    private FloatBuffer spherePN;
    private FloatBuffer planePN;
    private int sphereVertexCount;

    // Simple (box/sphere/plane) program
    private int program;
    private int aPos, aNrm, uMVP, uModel, uColor, uLightDir, uAmbient, uEmissive;

    // Textured mesh program (GLB)
    private int meshProgram;
    private int mAPos, mANrm, mAUv, mUMVP, mUModel, mUColor, mULight, mUAmbient, mUEmissive;
    private int mUTex, mUUseTex, mUCameraPos, mUEmissiveFactor, mUMetallic, mURoughness;

    private final AssetStore assets = new AssetStore();
    private Context appCtx;
    private volatile boolean engineReady = false;
    private int width = 1, height = 1;
    private long lastNs;

    private static final float HITBOX_LEN = 131.32f;
    private static final float HITBOX_WID = 87.1704f;
    private static final float HITBOX_HGT = 31.8944f;

    public void setEngineReady(boolean ready) { engineReady = ready; }

    public void setContext(Context ctx) {
        appCtx = ctx != null ? ctx.getApplicationContext() : null;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.12f, 0.13f, 0.16f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_BLEND);

        // --- simple lit shader ---
        String vs =
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uModel;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNrm;\n" +
            "varying vec3 vNrm;\n" +
            "void main(){\n" +
            "  vNrm = mat3(uModel) * aNrm;\n" +
            "  gl_Position = uMVP * vec4(aPos,1.0);\n" +
            "}\n";
        String fs =
            "precision mediump float;\n" +
            "uniform vec4 uColor;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform float uAmbient;\n" +
            "uniform float uEmissive;\n" +
            "varying vec3 vNrm;\n" +
            "void main(){\n" +
            "  vec3 n = normalize(vNrm);\n" +
            "  float ndl = max(dot(n, normalize(uLightDir)), 0.0);\n" +
            "  float light = uAmbient + (1.0 - uAmbient) * ndl;\n" +
            "  vec3 col = uColor.rgb * light + uColor.rgb * uEmissive;\n" +
            "  gl_FragColor = vec4(col, uColor.a);\n" +
            "}\n";
        program = link(vs, fs);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aNrm = GLES20.glGetAttribLocation(program, "aNrm");
        uMVP = GLES20.glGetUniformLocation(program, "uMVP");
        uModel = GLES20.glGetUniformLocation(program, "uModel");
        uColor = GLES20.glGetUniformLocation(program, "uColor");
        uLightDir = GLES20.glGetUniformLocation(program, "uLightDir");
        uAmbient = GLES20.glGetUniformLocation(program, "uAmbient");
        uEmissive = GLES20.glGetUniformLocation(program, "uEmissive");

        // --- GLB mesh shader (textures + simple PBR-lite) ---
        String mvs =
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uModel;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNrm;\n" +
            "attribute vec2 aUv;\n" +
            "varying vec3 vN;\n" +
            "varying vec2 vUv;\n" +
            "varying vec3 vWorldPos;\n" +
            "void main(){\n" +
            "  vN = mat3(uModel) * aNrm;\n" +
            "  vUv = aUv;\n" +
            "  vWorldPos = (uModel * vec4(aPos,1.0)).xyz;\n" +
            "  gl_Position = uMVP * vec4(aPos,1.0);\n" +
            "}\n";
        String mfs =
            "precision mediump float;\n" +
            "varying vec3 vN;\n" +
            "varying vec2 vUv;\n" +
            "varying vec3 vWorldPos;\n" +
            "uniform vec4 uColor;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform vec3 uCameraPos;\n" +
            "uniform float uAmbient;\n" +
            "uniform float uEmissive;\n" +
            "uniform vec3 uEmissiveFactor;\n" +
            "uniform float uMetallic;\n" +
            "uniform float uRoughness;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform float uUseTex;\n" +
            "void main(){\n" +
            "  vec3 n = normalize(vN);\n" +
            "  vec3 l = normalize(uLightDir);\n" +
            "  vec3 v = normalize(uCameraPos - vWorldPos);\n" +
            "  vec3 h = normalize(l + v);\n" +
            "  float ndl = max(dot(n, l), 0.0);\n" +
            "  float ndv = max(dot(n, v), 0.0);\n" +
            "  float ndh = max(dot(n, h), 0.0);\n" +
            "  float shininess = mix(128.0, 4.0, uRoughness);\n" +
            "  float specStrength = mix(0.06, 0.9, uMetallic);\n" +
            "  float spec = pow(ndh, shininess) * specStrength;\n" +
            "  float fresnel = pow(1.0 - ndv, 5.0);\n" +
            "  float fresnelStrength = mix(0.04, 0.6, uMetallic);\n" +
            "  vec4 texC = (uUseTex > 0.5) ? texture2D(uTex, vUv) : vec4(1.0);\n" +
            "  vec3 baseCol = uColor.rgb * texC.rgb;\n" +
            "  vec3 specCol = mix(vec3(1.0), baseCol, uMetallic);\n" +
            "  float diffuseAmt = (1.0 - uMetallic);\n" +
            "  vec3 diffuse = baseCol * diffuseAmt * (uAmbient + (1.0 - uAmbient) * ndl);\n" +
            "  vec3 color = diffuse + specCol * spec + specCol * fresnel * fresnelStrength;\n" +
            "  color += (baseCol * uEmissive) + uEmissiveFactor;\n" +
            "  color = color / (color + vec3(1.0));\n" +
            "  color = pow(color, vec3(1.0/2.2));\n" +
            "  gl_FragColor = vec4(color, uColor.a * texC.a);\n" +
            "}\n";
        meshProgram = link(mvs, mfs);
        mAPos = GLES20.glGetAttribLocation(meshProgram, "aPos");
        mANrm = GLES20.glGetAttribLocation(meshProgram, "aNrm");
        mAUv = GLES20.glGetAttribLocation(meshProgram, "aUv");
        mUMVP = GLES20.glGetUniformLocation(meshProgram, "uMVP");
        mUModel = GLES20.glGetUniformLocation(meshProgram, "uModel");
        mUColor = GLES20.glGetUniformLocation(meshProgram, "uColor");
        mULight = GLES20.glGetUniformLocation(meshProgram, "uLightDir");
        mUAmbient = GLES20.glGetUniformLocation(meshProgram, "uAmbient");
        mUEmissive = GLES20.glGetUniformLocation(meshProgram, "uEmissive");
        mUTex = GLES20.glGetUniformLocation(meshProgram, "uTex");
        mUUseTex = GLES20.glGetUniformLocation(meshProgram, "uUseTex");
        mUCameraPos = GLES20.glGetUniformLocation(meshProgram, "uCameraPos");
        mUEmissiveFactor = GLES20.glGetUniformLocation(meshProgram, "uEmissiveFactor");
        mUMetallic = GLES20.glGetUniformLocation(meshProgram, "uMetallic");
        mURoughness = GLES20.glGetUniformLocation(meshProgram, "uRoughness");

        cubePN = buildCubePN();
        spherePN = buildSpherePN(12, 16);
        planePN = buildPlanePN(12000f);
        lastNs = System.nanoTime();

        if (appCtx != null) {
            try {
                assets.loadCar(appCtx);
            } catch (Throwable t) {
                Log.e(TAG, "Fennec load failed", t);
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = Math.min((now - lastNs) / 1e9f, 0.05f);
        lastNs = now;

        if (engineReady) {
            try {
                NativeBridge.nativeUpdate(dt);
                float[] buf = new float[40];
                if (NativeBridge.nativeGetSnapshot(buf)) {
                    synchronized (snapLock) {
                        System.arraycopy(buf, 0, snapshot, 0, 40);
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Lazy load if context arrived after surface created
        if (assets.car == null && appCtx != null) {
            try { assets.loadCar(appCtx); } catch (Throwable ignored) {}
        }

        float[] s;
        synchronized (snapLock) {
            s = snapshot.clone();
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        float fov = s[30] > 10f ? s[30] : 70f;
        float aspect = (float) width / (float) height;
        Matrix.perspectiveM(proj, 0, fov, aspect, 10f, 20000f);

        float cx = s[16], cy = s[17], cz = s[18];
        float tx = s[19], ty = s[20], tz = s[21];
        if (!engineReady || s[29] < 0.5f) {
            cx = 0; cy = -800; cz = 300;
            tx = 0; ty = 0; tz = 50;
        }
        currentCameraPos[0] = cx;
        currentCameraPos[1] = cy;
        currentCameraPos[2] = cz;
        Matrix.setLookAtM(view, 0, cx, cy, cz, tx, ty, tz, 0f, 0f, 1f);

        GLES20.glUseProgram(program);
        GLES20.glUniform3fv(uLightDir, 1, lightDir, 0);
        drawGround();

        if (engineReady && s[29] > 0.5f) {
            float px = s[0], py = s[1], pz = s[2];
            float fx = s[3], fy = s[4], fz = s[5];
            float ux = s[6], uy = s[7], uz = s[8];
            float rx = s[9], ry = s[10], rz = s[11];

            if (assets.car != null && !assets.car.primitives.isEmpty()) {
                drawFennec(px, py, pz, fx, fy, fz, ux, uy, uz, rx, ry, rz);
            } else {
                // Fallback box if GLB failed
                drawCarBox(px, py, pz, fx, fy, fz, ux, uy, uz, rx, ry, rz);
            }

            float bx = s[12], by = s[13], bz = s[14];
            float br = s[15] > 1f ? s[15] : 91.25f;
            GLES20.glUseProgram(program);
            GLES20.glUniform3fv(uLightDir, 1, lightDir, 0);
            drawSphere(bx, by, bz, br, 0.95f, 0.85f, 0.15f);
        }
    }

    /**
     * Fennec: physics basis is source of truth.
     * Local +X (GLB long axis) → physics forward.
     * model columns: [forward | right | up | pos]
     */
    private void drawFennec(float x, float y, float z,
                            float fx, float fy, float fz,
                            float ux, float uy, float uz,
                            float rx, float ry, float rz) {
        GlbModel glb = assets.car;
        CarCatalog.VisualTransform visual = CarCatalog.car().visual;

        Matrix.setIdentityM(model, 0);
        model[0] = fx; model[1] = fy; model[2] = fz;
        model[4] = rx; model[5] = ry; model[6] = rz;
        model[8] = ux; model[9] = uy; model[10] = uz;
        model[12] = x; model[13] = y; model[14] = z;

        if (visual != null && (visual.yawDeg != 0f || visual.pitchDeg != 0f || visual.rollDeg != 0f
                || visual.scale != 1f || visual.offsetX != 0f || visual.offsetY != 0f || visual.offsetZ != 0f)) {
            Matrix.setIdentityM(corrM, 0);
            if (visual.yawDeg != 0f) Matrix.rotateM(corrM, 0, visual.yawDeg, 0f, 0f, 1f);
            if (visual.pitchDeg != 0f) Matrix.rotateM(corrM, 0, visual.pitchDeg, 0f, 1f, 0f);
            if (visual.rollDeg != 0f) Matrix.rotateM(corrM, 0, visual.rollDeg, 1f, 0f, 0f);
            if (visual.scale != 1f) Matrix.scaleM(corrM, 0, visual.scale, visual.scale, visual.scale);
            if (visual.offsetX != 0f || visual.offsetY != 0f || visual.offsetZ != 0f) {
                Matrix.setIdentityM(offsetM, 0);
                Matrix.translateM(offsetM, 0, visual.offsetX, visual.offsetY, visual.offsetZ);
                Matrix.multiplyMM(corrM, 0, offsetM, 0, corrM, 0);
            }
            Matrix.multiplyMM(orientedM, 0, model, 0, corrM, 0);
            System.arraycopy(orientedM, 0, model, 0, 16);
        }

        GLES20.glUseProgram(meshProgram);
        GLES20.glUniform3f(mULight, lightDir[0], lightDir[1], lightDir[2]);
        GLES20.glUniform3f(mUCameraPos, currentCameraPos[0], currentCameraPos[1], currentCameraPos[2]);

        for (GlbModel.Primitive prim : glb.primitives) {
            Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
            GLES20.glUniformMatrix4fv(mUMVP, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(mUModel, 1, false, model, 0);
            GLES20.glUniform4f(mUColor, prim.baseColor[0], prim.baseColor[1], prim.baseColor[2], prim.baseColor[3]);
            GLES20.glUniform1f(mUAmbient, 0.4f);
            GLES20.glUniform1f(mUEmissive, 0.05f);
            GLES20.glUniform3f(mUEmissiveFactor, prim.emissiveFactor[0], prim.emissiveFactor[1], prim.emissiveFactor[2]);
            GLES20.glUniform1f(mUMetallic, prim.metallic);
            GLES20.glUniform1f(mURoughness, prim.roughness);

            if ("BLEND".equals(prim.alphaMode)) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glDepthMask(false);
            } else {
                GLES20.glDisable(GLES20.GL_BLEND);
                GLES20.glDepthMask(true);
            }
            if (prim.doubleSided) GLES20.glDisable(GLES20.GL_CULL_FACE);
            else GLES20.glEnable(GLES20.GL_CULL_FACE);

            boolean useTex = prim.textureId > 0;
            GLES20.glUniform1f(mUUseTex, useTex ? 1f : 0f);
            if (useTex) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prim.textureId);
                GLES20.glUniform1i(mUTex, 0);
            }
            prim.interleaved.position(0);
            GLES20.glEnableVertexAttribArray(mAPos);
            GLES20.glVertexAttribPointer(mAPos, 3, GLES20.GL_FLOAT, false, 32, prim.interleaved);
            prim.interleaved.position(3);
            GLES20.glEnableVertexAttribArray(mANrm);
            GLES20.glVertexAttribPointer(mANrm, 3, GLES20.GL_FLOAT, false, 32, prim.interleaved);
            prim.interleaved.position(6);
            GLES20.glEnableVertexAttribArray(mAUv);
            GLES20.glVertexAttribPointer(mAUv, 2, GLES20.GL_FLOAT, false, 32, prim.interleaved);
            prim.indices.position(0);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, prim.indexCount, prim.indexType, prim.indices);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glUseProgram(program);
    }

    private void drawCarBox(float x, float y, float z,
                            float fx, float fy, float fz,
                            float ux, float uy, float uz,
                            float rx, float ry, float rz) {
        Matrix.setIdentityM(model, 0);
        model[0] = rx; model[1] = ry; model[2] = rz;
        model[4] = fx; model[5] = fy; model[6] = fz;
        model[8] = ux; model[9] = uy; model[10] = uz;
        model[12] = x; model[13] = y; model[14] = z;
        Matrix.setIdentityM(tmp2, 0);
        Matrix.scaleM(tmp2, 0, HITBOX_WID, HITBOX_LEN, HITBOX_HGT);
        Matrix.multiplyMM(tmp, 0, model, 0, tmp2, 0);
        System.arraycopy(tmp, 0, model, 0, 16);
        drawMesh(cubePN, 36, 0.25f, 0.55f, 0.95f, 1f, 0.35f, 0.05f);
    }

    private void drawSphere(float x, float y, float z, float radius,
                            float r, float g, float b) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        Matrix.scaleM(model, 0, radius, radius, radius);
        drawMesh(spherePN, sphereVertexCount, r, g, b, 1f, 0.45f, 0.08f);
    }

    private void drawGround() {
        Matrix.setIdentityM(model, 0);
        drawMesh(planePN, 6, 0.22f, 0.28f, 0.22f, 1f, 0.55f, 0f);
    }

    private void drawMesh(FloatBuffer buf, int verts,
                          float r, float g, float b, float a,
                          float ambient, float emissive) {
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform4f(uColor, r, g, b, a);
        GLES20.glUniform1f(uAmbient, ambient);
        GLES20.glUniform1f(uEmissive, emissive);
        buf.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, buf);
        buf.position(3);
        GLES20.glEnableVertexAttribArray(aNrm);
        GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 24, buf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts);
    }

    private static FloatBuffer buildCubePN() {
        float[] v = {
            -0.5f,-0.5f, 0.5f,  0,0,1,   0.5f,-0.5f, 0.5f,  0,0,1,   0.5f, 0.5f, 0.5f,  0,0,1,
            -0.5f,-0.5f, 0.5f,  0,0,1,   0.5f, 0.5f, 0.5f,  0,0,1,  -0.5f, 0.5f, 0.5f,  0,0,1,
             0.5f,-0.5f,-0.5f,  0,0,-1, -0.5f,-0.5f,-0.5f,  0,0,-1, -0.5f, 0.5f,-0.5f,  0,0,-1,
             0.5f,-0.5f,-0.5f,  0,0,-1, -0.5f, 0.5f,-0.5f,  0,0,-1,  0.5f, 0.5f,-0.5f,  0,0,-1,
            -0.5f, 0.5f, 0.5f,  0,1,0,   0.5f, 0.5f, 0.5f,  0,1,0,   0.5f, 0.5f,-0.5f,  0,1,0,
            -0.5f, 0.5f, 0.5f,  0,1,0,   0.5f, 0.5f,-0.5f,  0,1,0,  -0.5f, 0.5f,-0.5f,  0,1,0,
            -0.5f,-0.5f,-0.5f,  0,-1,0,  0.5f,-0.5f,-0.5f,  0,-1,0,  0.5f,-0.5f, 0.5f,  0,-1,0,
            -0.5f,-0.5f,-0.5f,  0,-1,0,  0.5f,-0.5f, 0.5f,  0,-1,0, -0.5f,-0.5f, 0.5f,  0,-1,0,
             0.5f,-0.5f, 0.5f,  1,0,0,   0.5f,-0.5f,-0.5f,  1,0,0,   0.5f, 0.5f,-0.5f,  1,0,0,
             0.5f,-0.5f, 0.5f,  1,0,0,   0.5f, 0.5f,-0.5f,  1,0,0,   0.5f, 0.5f, 0.5f,  1,0,0,
            -0.5f,-0.5f,-0.5f, -1,0,0,  -0.5f,-0.5f, 0.5f, -1,0,0,  -0.5f, 0.5f, 0.5f, -1,0,0,
            -0.5f,-0.5f,-0.5f, -1,0,0,  -0.5f, 0.5f, 0.5f, -1,0,0,  -0.5f, 0.5f,-0.5f, -1,0,0,
        };
        return toBuf(v);
    }

    private FloatBuffer buildSpherePN(int stacks, int slices) {
        int n = stacks * slices * 6;
        sphereVertexCount = n;
        float[] v = new float[n * 6];
        int i = 0;
        for (int st = 0; st < stacks; st++) {
            float t0 = (float) (Math.PI * st / stacks);
            float t1 = (float) (Math.PI * (st + 1) / stacks);
            float y0 = (float) Math.cos(t0), y1 = (float) Math.cos(t1);
            float r0 = (float) Math.sin(t0), r1 = (float) Math.sin(t1);
            for (int sl = 0; sl < slices; sl++) {
                float p0 = (float) (2 * Math.PI * sl / slices);
                float p1 = (float) (2 * Math.PI * (sl + 1) / slices);
                float x00 = r0 * (float) Math.cos(p0), z00 = r0 * (float) Math.sin(p0);
                float x01 = r0 * (float) Math.cos(p1), z01 = r0 * (float) Math.sin(p1);
                float x10 = r1 * (float) Math.cos(p0), z10 = r1 * (float) Math.sin(p0);
                float x11 = r1 * (float) Math.cos(p1), z11 = r1 * (float) Math.sin(p1);
                i = put(v, i, x00, y0, z00); i = put(v, i, x10, y1, z10); i = put(v, i, x11, y1, z11);
                i = put(v, i, x00, y0, z00); i = put(v, i, x11, y1, z11); i = put(v, i, x01, y0, z01);
            }
        }
        return toBuf(v);
    }

    private static int put(float[] v, int i, float x, float y, float z) {
        v[i++] = x; v[i++] = y; v[i++] = z;
        v[i++] = x; v[i++] = y; v[i++] = z;
        return i;
    }

    private static FloatBuffer buildPlanePN(float half) {
        float[] v = {
            -half, -half, 0,  0,0,1,
             half, -half, 0,  0,0,1,
             half,  half, 0,  0,0,1,
            -half, -half, 0,  0,0,1,
             half,  half, 0,  0,0,1,
            -half,  half, 0,  0,0,1,
        };
        return toBuf(v);
    }

    private static FloatBuffer toBuf(float[] data) {
        FloatBuffer b = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(data).position(0);
        return b;
    }

    private static int link(String vsSrc, String fsSrc) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc);
        int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);
        return prog;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }
}
