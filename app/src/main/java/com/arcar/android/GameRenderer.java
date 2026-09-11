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
 * ArcAr Alpha 0.1 — minimal renderer.
 * Draws: simple car box (aligned to physics hitbox), ball sphere, flat ground plane.
 * Chase / ball camera only. No GLB, VFX, particles, shadows, stadium, HUD.
 */
public class GameRenderer implements GLSurfaceView.Renderer {

    // Snapshot layout (must match jni_bridge.cpp)
    // 0-2 carPos, 3-5 carForward, 6-8 carUp, 9-11 carRight,
    // 12-14 ballPos, 15 ballRadius,
    // 16-18 camPos, 19-21 camTarget,
    // 22 boost, 23 speed, 24 ballCam, 25 onGround, 26 boosting, 27 super,
    // 28 tick, 29 ready, 30 fov, 31 shake, 32 ballSpeed, 33 impact, 34 goal,
    // 35-37 carVel, 38-39 ballVel.x/y
    private final float[] snapshot = new float[40];
    private final Object snapLock = new Object();

    private final float[] view = new float[16];
    private final float[] proj = new float[16];
    private final float[] mvp = new float[16];
    private final float[] model = new float[16];
    private final float[] tmp = new float[16];
    private final float[] tmp2 = new float[16];
    private final float[] lightDir = new float[]{0.4f, 0.25f, 0.85f};

    private FloatBuffer cubePN;
    private FloatBuffer spherePN;
    private FloatBuffer planePN;
    private int sphereVertexCount;

    private int program;
    private int aPos, aNrm, uMVP, uModel, uColor, uLightDir, uAmbient, uEmissive;

    private volatile boolean engineReady = false;
    private int width, height;
    private long lastNs;

    // BODY_C hitbox full size (UU): length(X/forward)=131.32, width(Y/right)=87.17, height(Z/up)=31.89
    private static final float HITBOX_LEN = 131.32f;
    private static final float HITBOX_WID = 87.1704f;
    private static final float HITBOX_HGT = 31.8944f;

    public void setEngineReady(boolean ready) { engineReady = ready; }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.12f, 0.13f, 0.16f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_BLEND);

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

        cubePN = buildCubePN();
        spherePN = buildSpherePN(12, 16);
        planePN = buildPlanePN(12000f); // huge flat ground visual
        lastNs = System.nanoTime();
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

        float[] s;
        synchronized (snapLock) {
            s = snapshot.clone();
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glUniform3fv(uLightDir, 1, lightDir, 0);

        float fov = s[30] > 10f ? s[30] : 70f;
        float aspect = (float) width / (float) height;
        Matrix.perspectiveM(proj, 0, fov, aspect, 10f, 20000f);

        float cx = s[16], cy = s[17], cz = s[18];
        float tx = s[19], ty = s[20], tz = s[21];
        // Fallback camera if snapshot not ready
        if (!engineReady || s[29] < 0.5f) {
            cx = 0; cy = -800; cz = 300;
            tx = 0; ty = 0; tz = 50;
        }
        Matrix.setLookAtM(view, 0, cx, cy, cz, tx, ty, tz, 0f, 0f, 1f);

        // Ground plane at z=0
        drawGround();

        if (engineReady && s[29] > 0.5f) {
            // Car box — orientation from physics (forward / right / up)
            float px = s[0], py = s[1], pz = s[2];
            float fx = s[3], fy = s[4], fz = s[5];
            float ux = s[6], uy = s[7], uz = s[8];
            float rx = s[9], ry = s[10], rz = s[11];
            drawCarBox(px, py, pz, fx, fy, fz, ux, uy, uz, rx, ry, rz);

            // Ball sphere
            float bx = s[12], by = s[13], bz = s[14];
            float br = s[15] > 1f ? s[15] : 91.25f;
            drawSphere(bx, by, bz, br, 0.95f, 0.85f, 0.15f);
        }
    }

    /**
     * Visual box aligned to physics hitbox.
     * Physics local: X=forward, Y=right, Z=up. hitboxSize = (len, wid, hgt).
     * Matrix columns: col0=right, col1=forward, col2=up so scale (wid, len, hgt).
     */
    private void drawCarBox(float x, float y, float z,
                            float fx, float fy, float fz,
                            float ux, float uy, float uz,
                            float rx, float ry, float rz) {
        Matrix.setIdentityM(model, 0);
        // Column-major: X axis = right, Y axis = forward, Z axis = up
        model[0] = rx; model[1] = ry; model[2] = rz;
        model[4] = fx; model[5] = fy; model[6] = fz;
        model[8] = ux; model[9] = uy; model[10] = uz;
        model[12] = x; model[13] = y; model[14] = z;

        Matrix.setIdentityM(tmp2, 0);
        // Full hitbox size as scale of unit cube (-0.5..0.5)
        Matrix.scaleM(tmp2, 0, HITBOX_WID, HITBOX_LEN, HITBOX_HGT);
        Matrix.multiplyMM(tmp, 0, model, 0, tmp2, 0);
        System.arraycopy(tmp, 0, model, 0, 16);

        // Body
        drawMesh(cubePN, 36, 0.25f, 0.55f, 0.95f, 1f, 0.35f, 0.05f);

        // Nose marker (front tip) so forward direction is obvious
        Matrix.setIdentityM(model, 0);
        model[0] = rx; model[1] = ry; model[2] = rz;
        model[4] = fx; model[5] = fy; model[6] = fz;
        model[8] = ux; model[9] = uy; model[10] = uz;
        // Offset to front of hitbox
        float noseOff = HITBOX_LEN * 0.5f + 8f;
        model[12] = x + fx * noseOff;
        model[13] = y + fy * noseOff;
        model[14] = z + fz * noseOff;
        Matrix.setIdentityM(tmp2, 0);
        Matrix.scaleM(tmp2, 0, HITBOX_WID * 0.35f, 16f, HITBOX_HGT * 0.6f);
        Matrix.multiplyMM(tmp, 0, model, 0, tmp2, 0);
        System.arraycopy(tmp, 0, model, 0, 16);
        drawMesh(cubePN, 36, 1f, 0.35f, 0.1f, 1f, 0.4f, 0.15f);
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
        // plane is XY at z=0, already large
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

    // Unit cube centered at origin, pos+normal interleaved (6 floats), 36 verts
    private static FloatBuffer buildCubePN() {
        float[] v = {
            // +Z
            -0.5f,-0.5f, 0.5f,  0,0,1,   0.5f,-0.5f, 0.5f,  0,0,1,   0.5f, 0.5f, 0.5f,  0,0,1,
            -0.5f,-0.5f, 0.5f,  0,0,1,   0.5f, 0.5f, 0.5f,  0,0,1,  -0.5f, 0.5f, 0.5f,  0,0,1,
            // -Z
             0.5f,-0.5f,-0.5f,  0,0,-1, -0.5f,-0.5f,-0.5f,  0,0,-1, -0.5f, 0.5f,-0.5f,  0,0,-1,
             0.5f,-0.5f,-0.5f,  0,0,-1, -0.5f, 0.5f,-0.5f,  0,0,-1,  0.5f, 0.5f,-0.5f,  0,0,-1,
            // +Y
            -0.5f, 0.5f, 0.5f,  0,1,0,   0.5f, 0.5f, 0.5f,  0,1,0,   0.5f, 0.5f,-0.5f,  0,1,0,
            -0.5f, 0.5f, 0.5f,  0,1,0,   0.5f, 0.5f,-0.5f,  0,1,0,  -0.5f, 0.5f,-0.5f,  0,1,0,
            // -Y
            -0.5f,-0.5f,-0.5f,  0,-1,0,  0.5f,-0.5f,-0.5f,  0,-1,0,  0.5f,-0.5f, 0.5f,  0,-1,0,
            -0.5f,-0.5f,-0.5f,  0,-1,0,  0.5f,-0.5f, 0.5f,  0,-1,0, -0.5f,-0.5f, 0.5f,  0,-1,0,
            // +X
             0.5f,-0.5f, 0.5f,  1,0,0,   0.5f,-0.5f,-0.5f,  1,0,0,   0.5f, 0.5f,-0.5f,  1,0,0,
             0.5f,-0.5f, 0.5f,  1,0,0,   0.5f, 0.5f,-0.5f,  1,0,0,   0.5f, 0.5f, 0.5f,  1,0,0,
            // -X
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
                // two tris
                i = put(v, i, x00, y0, z00); i = put(v, i, x10, y1, z10); i = put(v, i, x11, y1, z11);
                i = put(v, i, x00, y0, z00); i = put(v, i, x11, y1, z11); i = put(v, i, x01, y0, z01);
            }
        }
        return toBuf(v);
    }

    private static int put(float[] v, int i, float x, float y, float z) {
        // pos + normal (unit sphere)
        v[i++] = x; v[i++] = y; v[i++] = z;
        v[i++] = x; v[i++] = y; v[i++] = z;
        return i;
    }

    /** Flat XY plane centered at origin, normal +Z. */
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
