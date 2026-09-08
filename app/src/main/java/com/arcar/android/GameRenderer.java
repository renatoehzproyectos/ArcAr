package com.arcar.android;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Minimal GLES2 renderer: arena floor/walls, car box, ball sphere approximation.
 * World units = ArcAr UU (same scale as physics).
 */
public class GameRenderer implements GLSurfaceView.Renderer {

    private final float[] snapshot = new float[30];
    private final Object snapLock = new Object();

    private int program;
    private int aPos, uMVP, uColor;

    private final float[] view = new float[16];
    private final float[] proj = new float[16];
    private final float[] mvp = new float[16];
    private final float[] model = new float[16];
    private final float[] tmp = new float[16];

    private FloatBuffer cubeBuf;
    private FloatBuffer sphereBuf;
    private FloatBuffer lineBuf;
    private int sphereVertexCount;

    private int width, height;
    private long lastNs = 0;
    private volatile boolean engineReady = false;

    public void setEngineReady(boolean ready) { engineReady = ready; }

    // HUD callbacks
    public interface HudListener {
        void onHud(float boost, float speed, boolean ballCam, boolean ready);
    }
    private HudListener hudListener;

    public void setHudListener(HudListener l) { hudListener = l; }

    public void updateSnapshot(float[] src) {
        synchronized (snapLock) {
            System.arraycopy(src, 0, snapshot, 0, Math.min(30, src.length));
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.05f, 0.07f, 0.12f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_CULL_FACE);

        String vshader =
                "uniform mat4 uMVP;\n" +
                "attribute vec3 aPos;\n" +
                "void main(){ gl_Position = uMVP * vec4(aPos,1.0); }\n";
        String fshader =
                "precision mediump float;\n" +
                "uniform vec4 uColor;\n" +
                "void main(){ gl_FragColor = uColor; }\n";
        program = link(vshader, fshader);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        uMVP = GLES20.glGetUniformLocation(program, "uMVP");
        uColor = GLES20.glGetUniformLocation(program, "uColor");

        cubeBuf = buildCube();
        sphereBuf = buildSphere(12, 16);
        lineBuf = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w; height = h;
        GLES20.glViewport(0, 0, w, h);
        float aspect = (float) w / Math.max(1, h);
        Matrix.perspectiveM(proj, 0, 60f, aspect, 5f, 20000f);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 1f / 60f : (now - lastNs) / 1_000_000_000f;
        lastNs = now;
        if (dt > 0.1f) dt = 0.1f;

        if (!engineReady) {
            GLES20.glClearColor(0.05f, 0.07f, 0.12f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            return;
        }

        try {
            NativeBridge.nativeUpdate(dt);
            float[] local = new float[30];
            NativeBridge.nativeGetSnapshot(local);
            updateSnapshot(local);
        } catch (Throwable t) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            return;
        }

        float boost, speed;
        boolean ballCam, ready;
        float cx, cy, cz, fx, fy, fz, ux, uy, uz, rx, ry, rz;
        float bx, by, bz, br;
        float camx, camy, camz, tx, ty, tz;
        synchronized (snapLock) {
            cx = snapshot[0]; cy = snapshot[1]; cz = snapshot[2];
            fx = snapshot[3]; fy = snapshot[4]; fz = snapshot[5];
            ux = snapshot[6]; uy = snapshot[7]; uz = snapshot[8];
            rx = snapshot[9]; ry = snapshot[10]; rz = snapshot[11];
            bx = snapshot[12]; by = snapshot[13]; bz = snapshot[14];
            br = snapshot[15] > 1f ? snapshot[15] : 91.25f;
            camx = snapshot[16]; camy = snapshot[17]; camz = snapshot[18];
            tx = snapshot[19]; ty = snapshot[20]; tz = snapshot[21];
            boost = snapshot[22];
            speed = snapshot[23];
            ballCam = snapshot[24] > 0.5f;
            ready = snapshot[29] > 0.5f;
        }

        if (hudListener != null) {
            hudListener.onHud(boost, speed, ballCam, ready);
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (!ready) return;

        // Fallback camera if zeros
        if (camx == 0 && camy == 0 && camz == 0) {
            camx = cx; camy = cy - 400; camz = cz + 150;
            tx = cx; ty = cy; tz = cz;
        }
        Matrix.setLookAtM(view, 0,
                camx, camy, camz,
                tx, ty, tz,
                0, 0, 1);

        GLES20.glUseProgram(program);

        // Floor
        drawBox(0, 0, -2f, 4200, 5200, 4f, 0.12f, 0.35f, 0.18f, 1f);
        // Ceiling hint
        drawBox(0, 0, 2050, 4200, 5200, 4f, 0.15f, 0.15f, 0.2f, 1f);
        // Side walls
        drawBox(-4096, 0, 1024, 8f, 5200, 2048, 0.25f, 0.28f, 0.4f, 1f);
        drawBox( 4096, 0, 1024, 8f, 5200, 2048, 0.25f, 0.28f, 0.4f, 1f);
        // Back walls
        drawBox(0, -5120, 1024, 4096, 8f, 2048, 0.3f, 0.25f, 0.25f, 1f);
        drawBox(0,  5120, 1024, 4096, 8f, 2048, 0.25f, 0.3f, 0.25f, 1f);
        // Goals (simple openings as darker boxes)
        drawBox(0, -5350, 320, 900, 200, 640, 0.1f, 0.15f, 0.4f, 1f);
        drawBox(0,  5350, 320, 900, 200, 640, 0.4f, 0.15f, 0.1f, 1f);

        // Center lines
        drawBox(0, 0, 1f, 20f, 5120, 2f, 0.9f, 0.9f, 0.9f, 1f);
        drawBox(0, 0, 1f, 4096, 20f, 2f, 0.9f, 0.9f, 0.9f, 1f);

        // Car (oriented box ~ hitbox size body C ~ 118 x 84 x 36)
        // extents: right=width, forward=length, up=height (BODY_C-ish)
        drawOrientedBox(cx, cy, cz, fx, fy, fz, ux, uy, uz, rx, ry, rz,
                87.17f, 131.32f, 31.89f, 0.2f, 0.55f, 1f, 1f);

        // Nose marker (orange) so front is obvious — offset along forward
        float noseX = cx + fx * 70f;
        float noseY = cy + fy * 70f;
        float noseZ = cz + fz * 70f;
        drawOrientedBox(noseX, noseY, noseZ, fx, fy, fz, ux, uy, uz, rx, ry, rz,
                40f, 25f, 20f, 1f, 0.55f, 0.1f, 1f);

        // Ball
        drawSphere(bx, by, bz, br, 1f, 0.85f, 0.2f, 1f);
    }

    private void drawBox(float x, float y, float z, float sx, float sy, float sz,
                         float r, float g, float b, float a) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        Matrix.scaleM(model, 0, sx, sy, sz);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniform4f(uColor, r, g, b, a);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, cubeBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
    }

    private void drawOrientedBox(float x, float y, float z,
                                 float fx, float fy, float fz,
                                 float ux, float uy, float uz,
                                 float rx, float ry, float rz,
                                 float sx, float sy, float sz,
                                 float cr, float cg, float cb, float ca) {
        // Columns of rotation: right, forward, up in world — build matrix
        // model = T * R * S where R columns are right, forward, up
        Matrix.setIdentityM(model, 0);
        // Column-major: m[0..2]=right, m[4..6]=forward, m[8..10]=up
        model[0] = rx; model[1] = ry; model[2] = rz;
        model[4] = fx; model[5] = fy; model[6] = fz;
        model[8] = ux; model[9] = uy; model[10] = uz;
        model[12] = x; model[13] = y; model[14] = z;
        // Apply scale into basis
        float[] scaled = new float[16];
        Matrix.setIdentityM(scaled, 0);
        Matrix.scaleM(scaled, 0, sx * 0.5f, sy * 0.5f, sz * 0.5f);
        Matrix.multiplyMM(tmp, 0, model, 0, scaled, 0);
        System.arraycopy(tmp, 0, model, 0, 16);

        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniform4f(uColor, cr, cg, cb, ca);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, cubeBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
    }

    private void drawSphere(float x, float y, float z, float radius,
                            float r, float g, float b, float a) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        Matrix.scaleM(model, 0, radius, radius, radius);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniform4f(uColor, r, g, b, a);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, sphereBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, sphereVertexCount);
    }

    private static FloatBuffer buildCube() {
        // Unit cube centered at origin, 36 verts
        float[] v = {
                // +Z
                -1,-1,1,  1,-1,1,  1,1,1,  -1,-1,1,  1,1,1,  -1,1,1,
                // -Z
                -1,-1,-1,  -1,1,-1,  1,1,-1,  -1,-1,-1,  1,1,-1,  1,-1,-1,
                // +Y
                -1,1,-1,  -1,1,1,  1,1,1,  -1,1,-1,  1,1,1,  1,1,-1,
                // -Y
                -1,-1,-1,  1,-1,-1,  1,-1,1,  -1,-1,-1,  1,-1,1,  -1,-1,1,
                // +X
                1,-1,-1,  1,1,-1,  1,1,1,  1,-1,-1,  1,1,1,  1,-1,1,
                // -X
                -1,-1,-1,  -1,-1,1,  -1,1,1,  -1,-1,-1,  -1,1,1,  -1,1,-1
        };
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    private FloatBuffer buildSphere(int stacks, int slices) {
        float[] tmpv = new float[stacks * slices * 6 * 3];
        int idx = 0;
        for (int i = 0; i < stacks; i++) {
            float v0 = (float) i / stacks;
            float v1 = (float) (i + 1) / stacks;
            float phi0 = (float) (Math.PI * (v0 - 0.5));
            float phi1 = (float) (Math.PI * (v1 - 0.5));
            for (int j = 0; j < slices; j++) {
                float u0 = (float) j / slices;
                float u1 = (float) (j + 1) / slices;
                float th0 = (float) (2 * Math.PI * u0);
                float th1 = (float) (2 * Math.PI * u1);
                float[] p00 = sph(phi0, th0);
                float[] p01 = sph(phi0, th1);
                float[] p10 = sph(phi1, th0);
                float[] p11 = sph(phi1, th1);
                idx = put3(tmpv, idx, p00); idx = put3(tmpv, idx, p10); idx = put3(tmpv, idx, p11);
                idx = put3(tmpv, idx, p00); idx = put3(tmpv, idx, p11); idx = put3(tmpv, idx, p01);
            }
        }
        sphereVertexCount = idx / 3;
        FloatBuffer b = ByteBuffer.allocateDirect(idx * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(tmpv, 0, idx).position(0);
        return b;
    }

    private static float[] sph(float phi, float theta) {
        float cp = (float) Math.cos(phi), sp = (float) Math.sin(phi);
        float ct = (float) Math.cos(theta), st = (float) Math.sin(theta);
        return new float[]{ cp * ct, cp * st, sp };
    }

    private static int put3(float[] a, int i, float[] p) {
        a[i++] = p[0]; a[i++] = p[1]; a[i++] = p[2];
        return i;
    }

    private static int link(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }
}
