package com.arcar.android;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Full visual overhaul: lit stadium, detailed car, boost VFX, ball effects, arcade camera FOV.
 * Original stylized presentation inspired by modern car-soccer games (not copyrighted assets).
 */
public class GameRenderer implements GLSurfaceView.Renderer {

    private final float[] snapshot = new float[40];
    private final Object snapLock = new Object();

    private int program;
    private int aPos, aNrm, uMVP, uModel, uColor, uLightDir, uAmbient, uEmissive;

    private final float[] view = new float[16];
    private final float[] proj = new float[16];
    private final float[] mvp = new float[16];
    private final float[] model = new float[16];
    private final float[] tmp = new float[16];
    private final float[] tmp2 = new float[16];
    private final float[] lightDir = new float[]{0.35f, 0.2f, 0.9f};

    private FloatBuffer cubePN; // pos+normal interleaved (6 floats)
    private FloatBuffer spherePN;
    private int sphereVertexCount;

    private int width, height;
    private long lastNs;
    private volatile boolean engineReady;
    private final Random rng = new Random(42);

    private Context appCtx;
    private final AssetStore assets = new AssetStore();
    private int meshProgram;
    private int mAPos, mANrm, mAUv, mUMVP, mUModel, mUColor, mULight, mUAmbient, mUEmissive, mUTex, mUUseTex;
    private int spriteProgram;
    private int sAPos, sAUv, sUMVP, sUColor, sUTex;
    private FloatBuffer quadPN; // pos3+uv2 for billboards
    private String pendingCarId = "octane";

    // Trail / particles (pooled)
    private static final int TRAIL = 64;
    private final float[] tX = new float[TRAIL], tY = new float[TRAIL], tZ = new float[TRAIL];
    private final float[] tLife = new float[TRAIL];
    private int tHead;
    private float tAcc;

    private static final int PART = 96;
    private final float[] pX = new float[PART], pY = new float[PART], pZ = new float[PART];
    private final float[] pVX = new float[PART], pVY = new float[PART], pVZ = new float[PART];
    private final float[] pLife = new float[PART], pMax = new float[PART];
    private final float[] pR = new float[PART], pG = new float[PART], pB = new float[PART], pS = new float[PART];
    private int pCount;

    private float wheelAngle;
    private float goalFlash;
    private float impactFlash;
    private float ballTrailAcc;
    private final float[] bTrailX = new float[24], bTrailY = new float[24], bTrailZ = new float[24];
    private int bTrailN, bTrailH;

    private float smoothFov = 70f;

    public void setEngineReady(boolean ready) { engineReady = ready; }

    public void setContext(Context ctx) {
        appCtx = ctx.getApplicationContext();
        pendingCarId = CarCatalog.getSelectedId(appCtx);
    }

    public void setCarId(String id) { pendingCarId = id; }

    public interface HudListener {
        void onHud(float boost, float speed, boolean ballCam, boolean ready, boolean boosting, boolean goal);
    }
    private HudListener hudListener;
    public void setHudListener(HudListener l) { hudListener = l; }

    public void updateSnapshot(float[] src) {
        synchronized (snapLock) {
            System.arraycopy(src, 0, snapshot, 0, Math.min(40, src.length));
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.08f, 0.09f, 0.14f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glEnable(GLES20.GL_BLEND);
        // allow 32-bit indices for larger meshes
        // (device almost always supports OES_element_index_uint)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        String vs =
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uModel;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNrm;\n" +
            "varying vec3 vN;\n" +
            "void main(){\n" +
            "  vN = mat3(uModel) * aNrm;\n" +
            "  gl_Position = uMVP * vec4(aPos,1.0);\n" +
            "}\n";
        String fs =
            "precision mediump float;\n" +
            "varying vec3 vN;\n" +
            "uniform vec4 uColor;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform float uAmbient;\n" +
            "uniform float uEmissive;\n" +
            "void main(){\n" +
            "  vec3 n = normalize(vN);\n" +
            "  float ndl = max(dot(n, normalize(uLightDir)), 0.0);\n" +
            "  float spec = pow(ndl, 24.0) * 0.35;\n" +
            "  float light = uAmbient + (1.0 - uAmbient) * ndl + spec;\n" +
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
        spherePN = buildSpherePN(16, 24);
        quadPN = buildQuad();

        // Textured mesh shader
        String mvs =
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uModel;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNrm;\n" +
            "attribute vec2 aUv;\n" +
            "varying vec3 vN;\n" +
            "varying vec2 vUv;\n" +
            "void main(){\n" +
            "  vN = mat3(uModel) * aNrm;\n" +
            "  vUv = aUv;\n" +
            "  gl_Position = uMVP * vec4(aPos,1.0);\n" +
            "}\n";
        String mfs =
            "precision mediump float;\n" +
            "varying vec3 vN;\n" +
            "varying vec2 vUv;\n" +
            "uniform vec4 uColor;\n" +
            "uniform vec3 uLightDir;\n" +
            "uniform float uAmbient;\n" +
            "uniform float uEmissive;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform float uUseTex;\n" +
            "void main(){\n" +
            "  vec3 n = normalize(vN);\n" +
            "  float ndl = max(dot(n, normalize(uLightDir)), 0.0);\n" +
            "  float light = uAmbient + (1.0 - uAmbient) * ndl;\n" +
            "  vec4 texC = (uUseTex > 0.5) ? texture2D(uTex, vUv) : vec4(1.0);\n" +
            "  vec3 col = uColor.rgb * texC.rgb * light + uColor.rgb * uEmissive;\n" +
            "  gl_FragColor = vec4(col, uColor.a * texC.a);\n" +
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

        // Sprite/billboard shader
        String svs =
            "uniform mat4 uMVP;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec2 aUv;\n" +
            "varying vec2 vUv;\n" +
            "void main(){ vUv=aUv; gl_Position=uMVP*vec4(aPos,1.0); }\n";
        String sfs =
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform vec4 uColor;\n" +
            "uniform sampler2D uTex;\n" +
            "void main(){\n" +
            "  vec4 t = texture2D(uTex, vUv);\n" +
            "  gl_FragColor = vec4(t.rgb * uColor.rgb, t.a * uColor.a);\n" +
            "}\n";
        spriteProgram = link(svs, sfs);
        sAPos = GLES20.glGetAttribLocation(spriteProgram, "aPos");
        sAUv = GLES20.glGetAttribLocation(spriteProgram, "aUv");
        sUMVP = GLES20.glGetUniformLocation(spriteProgram, "uMVP");
        sUColor = GLES20.glGetUniformLocation(spriteProgram, "uColor");
        sUTex = GLES20.glGetUniformLocation(spriteProgram, "uTex");

        if (appCtx != null) {
            assets.loadAll(appCtx, pendingCarId);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w; height = h;
        GLES20.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 1f/60f : (now - lastNs) * 1e-9f;
        lastNs = now;
        if (dt > 0.08f) dt = 0.08f;

        if (!engineReady) {
            GLES20.glClearColor(0.06f, 0.07f, 0.1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            return;
        }

        try {
            NativeBridge.nativeUpdate(dt);
            float[] local = new float[40];
            NativeBridge.nativeGetSnapshot(local);
            updateSnapshot(local);
        } catch (Throwable t) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            return;
        }

        final float[] S = new float[40];
        synchronized (snapLock) { System.arraycopy(snapshot, 0, S, 0, 40); }
        if (S[29] < 0.5f) return;

        float cx=S[0],cy=S[1],cz=S[2];
        float fx=S[3],fy=S[4],fz=S[5];
        float ux=S[6],uy=S[7],uz=S[8];
        float rx=S[9],ry=S[10],rz=S[11];
        float bx=S[12],by=S[13],bz=S[14], br=S[15]>1?S[15]:91.25f;
        float camx=S[16],camy=S[17],camz=S[18];
        float tx=S[19],ty=S[20],tz=S[21];
        float boost=S[22], speed=S[23];
        boolean ballCam=S[24]>0.5f, onGround=S[25]>0.5f, boosting=S[26]>0.5f, isSuper=S[27]>0.5f;
        float fov = S[30] > 1f ? S[30] : 70f;
        float impact = S[33];
        boolean goal = S[34] > 0.5f;
        float cvx=S[35],cvy=S[36],cvz=S[37];
        float ballSp = S[32];

        if (hudListener != null) hudListener.onHud(boost, speed, ballCam, true, boosting, goal);

        // Effects state
        if (impact > 300f) {
            spawnImpact(bx, by, bz, impact);
            impactFlash = Math.min(1f, impactFlash + impact / 2500f);
        }
        if (goal) {
            goalFlash = 1f;
            spawnGoal(bx, by, bz);
        }
        goalFlash = Math.max(0f, goalFlash - dt * 0.7f);
        impactFlash = Math.max(0f, impactFlash - dt * 2.5f);

        updateParticles(dt);
        updateBoostTrail(dt, boosting, cx - fx*45f, cy - fy*45f, cz - fz*15f + ux*5f);
        if (ballSp > 1800f) {
            ballTrailAcc += dt;
            if (ballTrailAcc > 0.03f) {
                ballTrailAcc = 0;
                bTrailX[bTrailH]=bx; bTrailY[bTrailH]=by; bTrailZ[bTrailH]=bz;
                bTrailH = (bTrailH+1)%24;
                if (bTrailN < 24) bTrailN++;
            }
        } else if (bTrailN > 0 && (ballTrailAcc += dt) > 0.05f) {
            ballTrailAcc = 0; bTrailN--;
        }

        float spd = (float)Math.sqrt(cvx*cvx+cvy*cvy+cvz*cvz);
        wheelAngle += (spd * 0.02f) * dt * 60f;

        // FOV
        smoothFov += (fov - smoothFov) * 0.12f;
        float aspect = (float) width / Math.max(1, height);
        Matrix.perspectiveM(proj, 0, smoothFov, aspect, 6f, 30000f);
        Matrix.setLookAtM(view, 0, camx, camy, camz, tx, ty, tz, 0, 0, 1);

        // Clear — stadium night sky + goal flash
        float flash = goalFlash * 0.35f + impactFlash * 0.15f;
        if (appCtx != null) {
            String want = CarCatalog.getSelectedId(appCtx);
            if (assets.car == null || !want.equals(pendingCarId)) {
                pendingCarId = want;
                assets.loadAll(appCtx, want);
            }
        }

        GLES20.glClearColor(0.07f+flash, 0.08f+flash*0.5f, 0.12f+flash*0.2f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glUniform3f(uLightDir, lightDir[0], lightDir[1], lightDir[2]);

        drawStadium();
        drawBoostPads();
        drawBallTrail();
        drawBoostTrail();
        drawParticles();
        drawBall(bx, by, bz, br, ballSp);
        drawCar(cx,cy,cz, fx,fy,fz, ux,uy,uz, rx,ry,rz, boosting, isSuper, onGround, spd);
        // Ground shadow blobs
        drawShadow(cx, cy, 2f, 90f, 110f);
        drawShadow(bx, by, 2f, br*0.9f, br*0.9f);
    }

    // ========== STADIUM ==========
    private void drawStadium() {
        // Turf base
        litBox(0,0,-2, 8200,10300,4, 0.12f,0.42f,0.18f, 1f, 0.55f, 0f);
        // Lighter turf stripes
        for (int i = -5; i <= 5; i++) {
            float yy = i * 900f;
            litBox(0, yy, 0.5f, 8000, 400, 1.5f, 0.14f,0.48f,0.20f, 1f, 0.55f, 0f);
        }
        // Lines (emissive white)
        litBox(0,0,2, 35,10240, 2, 0.95f,0.95f,0.9f, 1f, 0.7f, 0.15f);
        litBox(-4090,0,2, 25,10240, 2, 0.9f,0.9f,0.85f, 1f, 0.7f, 0.1f);
        litBox(4090,0,2, 25,10240, 2, 0.9f,0.9f,0.85f, 1f, 0.7f, 0.1f);
        litBox(0,-5120,2, 8192, 25, 2, 0.9f,0.9f,0.85f, 1f, 0.7f, 0.1f);
        litBox(0,5120,2, 8192, 25, 2, 0.9f,0.9f,0.85f, 1f, 0.7f, 0.1f);
        // Center circle
        for (int i=0;i<32;i++) {
            float a=(float)(i*Math.PI*2/32);
            litBox((float)Math.cos(a)*920, (float)Math.sin(a)*920, 2.5f, 55,55,2, 0.95f,0.95f,0.9f,1f,0.7f,0.12f);
        }
        litBox(0,0,3, 100,100,3, 0.95f,0.95f,0.9f,1f,0.7f,0.12f);

        // Side walls — translucent-ish darker
        litBox(-4110,0,1100, 60,10400,2200, 0.18f,0.22f,0.32f, 0.92f, 0.45f, 0.05f);
        litBox(4110,0,1100, 60,10400,2200, 0.18f,0.22f,0.32f, 0.92f, 0.45f, 0.05f);
        // Team ends
        litBox(0,-5240,1100, 8300,80,2200, 0.12f,0.22f,0.55f, 1f, 0.4f, 0.08f);
        litBox(0,5240,1100, 8300,80,2200, 0.55f,0.22f,0.12f, 1f, 0.4f, 0.08f);
        // Ceiling girders feel
        litBox(0,0,2100, 8400,10500,40, 0.1f,0.11f,0.14f, 1f, 0.35f, 0f);
        for (int i=-4;i<=4;i++) {
            litBox(i*900f, 0, 2080, 40, 10000, 30, 0.25f,0.28f,0.35f, 1f, 0.4f, 0.2f);
        }
        // Stadium light strips
        for (int i=-3;i<=3;i++) {
            litBox(-3800, i*1400f, 1900, 80, 200, 40, 0.9f,0.9f,0.7f, 1f, 0.5f, 0.8f);
            litBox(3800, i*1400f, 1900, 80, 200, 40, 0.9f,0.9f,0.7f, 1f, 0.5f, 0.8f);
        }

        drawGoal(0, -5450, true);
        drawGoal(0, 5450, false);

        // Perimeter grandstand blocks
        litBox(0, -6200, 400, 9000, 400, 800, 0.12f,0.13f,0.18f, 1f, 0.35f, 0f);
        litBox(0, 6200, 400, 9000, 400, 800, 0.12f,0.13f,0.18f, 1f, 0.35f, 0f);
        litBox(-5000, 0, 400, 400, 11000, 800, 0.12f,0.13f,0.18f, 1f, 0.35f, 0f);
        litBox(5000, 0, 400, 400, 11000, 800, 0.12f,0.13f,0.18f, 1f, 0.35f, 0f);
    }

    private void drawGoal(float x, float y, boolean blue) {
        float r=blue?0.25f:0.95f, g=blue?0.45f:0.4f, b=blue?1f:0.2f;
        // Posts + bar
        litBox(x-460,y,320, 35,35,640, r,g,b,1f,0.5f,0.25f);
        litBox(x+460,y,320, 35,35,640, r,g,b,1f,0.5f,0.25f);
        litBox(x,y,640, 960,35,35, r,g,b,1f,0.5f,0.25f);
        // Net volume
        float depth = blue ? -220 : 220;
        litBox(x, y+depth, 300, 880, 400, 600, r*0.35f, g*0.35f, b*0.35f, 0.45f, 0.4f, 0.05f);
        // Goal mouth floor mark
        litBox(x, y+(blue?-80:80), 3, 900, 60, 2, 0.95f,0.95f,0.9f,1f,0.7f,0.1f);
        if (goalFlash > 0.05f) {
            litBox(x, y+depth*0.5f, 400, 1000, 500, 700, r,g,b, goalFlash*0.5f, 0.6f, goalFlash);
        }
    }

    private void drawBoostPads() {
        float[][] big = {{-3072,-4096},{3072,-4096},{-3072,4096},{3072,4096},{-3584,0},{3584,0}};
        for (float[] p : big) {
            litBox(p[0],p[1],6, 210,210,10, 0.15f,0.12f,0.05f,1f,0.4f,0f);
            litBox(p[0],p[1],14, 160,160,12, 1f,0.7f,0.12f,1f,0.5f,0.55f);
            litBox(p[0],p[1],22, 80,80,8, 1f,0.9f,0.4f,1f,0.5f,0.9f);
        }
        float[] ys = {-2480,-1024,1024,2480};
        for (float sy : ys) {
            for (float sx : new float[]{-1780,1780}) {
                litBox(sx,sy,5, 95,95,8, 1f,0.65f,0.15f,1f,0.5f,0.4f);
            }
        }
    }

    // ========== CAR ==========
    private void drawCar(float x,float y,float z, float fx,float fy,float fz,
                         float ux,float uy,float uz, float rx,float ry,float rz,
                         boolean boosting, boolean isSuper, boolean onGround, float speed) {
        if (assets.car != null && !assets.car.primitives.isEmpty()) {
            drawGlb(assets.car, x, y, z, fx, fy, fz, ux, uy, uz, rx, ry, rz,
                    isSuper ? 1.15f : 1f, isSuper ? 0.25f : 0.05f);
            // Boost exhaust still using particles/sprites
            float ex=x-fx*55, ey=y-fy*55, ez=z-fz*55;
            if (boosting) {
                float flick = 0.7f + 0.3f*(float)Math.sin(System.nanoTime()*1.2e-7);
                int flame = assets.tex("textures/particles/flame_03.png");
                if (flame != 0) {
                    drawBillboard(ex, ey, ez, 70f*flick, flame, 1f, 0.7f, 0.3f, 0.95f);
                    drawBillboard(ex-fx*35, ey-fy*35, ez-fz*35, 45f*flick,
                            assets.tex("textures/particles/flame_05.png"), 1f, 0.9f, 0.5f, 0.8f);
                } else {
                    ori(ex,ey,ez, fx,fy,fz,ux,uy,uz,rx,ry,rz, 22, 55*flick, 18, 1f,0.45f,0.05f,0.95f,0.5f,0.95f);
                }
            }
            return;
        }
        float br=isSuper?0.4f:0.18f, bg=isSuper?0.6f:0.48f, bb=isSuper?1f:0.95f;
        // Lower chassis
        ori(x,y,z, fx,fy,fz,ux,uy,uz,rx,ry,rz, 86,118,18, br*0.7f,bg*0.7f,bb*0.7f,1f,0.4f,0.05f);
        // Main body
        ori(x+ux*12,y+uy*12,z+uz*12, fx,fy,fz,ux,uy,uz,rx,ry,rz, 80,112,28, br,bg,bb,1f,0.45f,0.08f);
        // Hood
        ori(x+fx*28+ux*18, y+fy*28+uy*18, z+fz*28+uz*18, fx,fy,fz,ux,uy,uz,rx,ry,rz, 70,40,14, br*1.05f,bg*1.05f,bb,1f,0.45f,0.1f);
        // Cabin glass
        ori(x+fx*5+ux*32, y+fy*5+uy*32, z+fz*5+uz*32, fx,fy,fz,ux,uy,uz,rx,ry,rz, 52,42,18, 0.15f,0.25f,0.4f,0.85f,0.35f,0.15f);
        // Spoiler
        ori(x-fx*48+ux*28, y-fy*48+uy*28, z-fz*48+uz*28, fx,fy,fz,ux,uy,uz,rx,ry,rz, 78,10,8, br,bg,bb,1f,0.45f,0.1f);
        // Nose
        ori(x+fx*58+ux*8, y+fy*58+uy*8, z+fz*58+uz*8, fx,fy,fz,ux,uy,uz,rx,ry,rz, 48,24,16, 1f,0.4f,0.08f,1f,0.5f,0.2f);
        // Headlights
        ori(x+fx*62+rx*22+ux*10, y+fy*62+ry*22+uy*10, z+fz*62+rz*22+uz*10, fx,fy,fz,ux,uy,uz,rx,ry,rz, 8,6,6, 1f,1f,0.8f,1f,0.6f,0.9f);
        ori(x+fx*62-rx*22+ux*10, y+fy*62-ry*22+uy*10, z+fz*62-rz*22+uz*10, fx,fy,fz,ux,uy,uz,rx,ry,rz, 8,6,6, 1f,1f,0.8f,1f,0.6f,0.9f);
        // Taillights
        ori(x-fx*55+rx*28+ux*12, y-fy*55+ry*28+uy*12, z-fz*55+rz*28+uz*12, fx,fy,fz,ux,uy,uz,rx,ry,rz, 8,5,5, 1f,0.15f,0.1f,1f,0.5f,0.7f);
        ori(x-fx*55-rx*28+ux*12, y-fy*55-ry*28+uy*12, z-fz*55-rz*28+uz*12, fx,fy,fz,ux,uy,uz,rx,ry,rz, 8,5,5, 1f,0.15f,0.1f,1f,0.5f,0.7f);

        // Wheels
        float[][] wl = {{42,30,-10},{42,-30,-10},{-38,30,-10},{-38,-30,-10}};
        for (float[] w : wl) {
            float wx=x+fx*w[0]+rx*w[1]+ux*w[2];
            float wy=y+fy*w[0]+ry*w[1]+uy*w[2];
            float wz=z+fz*w[0]+rz*w[1]+uz*w[2];
            ori(wx,wy,wz, fx,fy,fz,ux,uy,uz,rx,ry,rz, 12,20,12, 0.08f,0.08f,0.08f,1f,0.3f,0f);
            ori(wx,wy,wz, fx,fy,fz,ux,uy,uz,rx,ry,rz, 6,14,6, 0.4f,0.4f,0.45f,1f,0.5f,0.1f);
        }

        // Boost exhaust
        float ex=x-fx*58, ey=y-fy*58, ez=z-fz*58;
        if (boosting) {
            float flick = 0.65f + 0.35f*(float)Math.sin(System.nanoTime()*1.2e-7);
            float power = 0.7f + Math.min(speed/2300f,1f)*0.5f;
            ori(ex,ey,ez, fx,fy,fz,ux,uy,uz,rx,ry,rz, 22, 55*flick*power, 18, 1f,0.45f,0.05f,0.95f,0.5f,0.95f);
            ori(ex-fx*30,ey-fy*30,ez-fz*30, fx,fy,fz,ux,uy,uz,rx,ry,rz, 14, 40*flick*power, 12, 1f,0.8f,0.25f,0.8f,0.5f,1f);
            ori(ex-fx*55,ey-fy*55,ez-fz*55, fx,fy,fz,ux,uy,uz,rx,ry,rz, 8, 25*flick, 8, 1f,1f,0.7f,0.6f,0.5f,1f);
        } else {
            ori(ex,ey,ez, fx,fy,fz,ux,uy,uz,rx,ry,rz, 16,14,12, 0.2f,0.2f,0.25f,1f,0.4f,0f);
        }
    }

    private void drawBall(float x,float y,float z,float r, float speed) {
        if (assets.ball != null && !assets.ball.primitives.isEmpty()) {
            // Ball model is unit-ish; scale so radius matches physics
            float s = (r * 2f) / Math.max(1f, assets.ball.radius * 2f);
            drawGlbUniformScale(assets.ball, x, y, z, s, 1f, 1f, 1f, 0.08f);
            if (speed > 1500f) {
                float a = Math.min((speed-1500f)/3000f, 0.45f);
                int glow = assets.tex("textures/particles/flare_01.png");
                if (glow != 0) drawBillboard(x, y, z, r * 2.2f, glow, 1f, 0.9f, 0.4f, a);
            }
            return;
        }
        litSphere(x,y,z,r, 0.92f,0.85f,0.15f,1f,0.5f,0.12f);
        litSphere(x,y,z,r*1.01f, 0.12f,0.12f,0.12f,0.4f,0.4f,0.05f);
        if (speed > 1500f) {
            float a = Math.min((speed-1500f)/3000f, 0.5f);
            litSphere(x,y,z,r*1.08f, 1f,0.9f,0.4f, a, 0.5f, 0.4f);
        }
    }

    private void drawShadow(float x, float y, float z, float sx, float sy) {
        litBox(x,y,z, sx*2, sy*2, 1.5f, 0f,0f,0f, 0.35f, 1f, 0f);
    }

    // ========== VFX ==========
    private void updateBoostTrail(float dt, boolean on, float x,float y,float z) {
        if (on) {
            tAcc += dt;
            if (tAcc > 0.016f) {
                tAcc = 0;
                tX[tHead]=x; tY[tHead]=y; tZ[tHead]=z;
                tLife[tHead]=1f;
                tHead=(tHead+1)%TRAIL;
            }
        }
        for (int i=0;i<TRAIL;i++) tLife[i] = Math.max(0f, tLife[i]-dt*1.8f);
    }

    private void drawBoostTrail() {
        int flame = assets.tex("textures/particles/flame_01.png");
        int smoke = assets.tex("textures/smoke/blackSmoke00.png");
        for (int i=0;i<TRAIL;i++) {
            if (tLife[i] <= 0.01f) continue;
            float life=tLife[i];
            float s=20f+55f*life;
            if (flame != 0) {
                drawBillboard(tX[i], tY[i], tZ[i], s, flame, 1f, 0.55f + 0.4f*life, 0.15f, life*0.85f);
            } else {
                litBox(tX[i],tY[i],tZ[i], s,s,s*0.7f, 1f,0.5f+0.4f*life,0.1f, life*0.7f, 0.5f, life);
            }
            if (life < 0.45f && smoke != 0) {
                drawBillboard(tX[i], tY[i], tZ[i]+8f, s*1.2f, smoke, 0.6f, 0.6f, 0.6f, life*0.35f);
            }
        }
    }

    private void drawBallTrail() {
        for (int i=0;i<bTrailN;i++) {
            int idx=(bTrailH-1-i+48)%24;
            float t=1f-i/24f;
            litSphere(bTrailX[idx],bTrailY[idx],bTrailZ[idx], 30f+20f*t, 1f,0.85f,0.3f, t*0.35f, 0.5f, t*0.3f);
        }
    }

    private void spawnImpact(float x,float y,float z, float impulse) {
        int n = Math.min(24, 8 + (int)(impulse/200f));
        for (int i=0;i<n;i++) {
            int id = allocP();
            if (id < 0) break;
            pX[id]=x; pY[id]=y; pZ[id]=z;
            float ang = rng.nextFloat()*(float)Math.PI*2;
            float sp = 200f + rng.nextFloat()*impulse*0.4f;
            pVX[id]=(float)Math.cos(ang)*sp; pVY[id]=(float)Math.sin(ang)*sp; pVZ[id]=100f+rng.nextFloat()*300f;
            pLife[id]=pMax[id]=0.35f+rng.nextFloat()*0.35f;
            pR[id]=1f; pG[id]=0.85f; pB[id]=0.3f; pS[id]=20f+rng.nextFloat()*30f;
        }
    }

    private void spawnGoal(float x,float y,float z) {
        for (int i=0;i<40;i++) {
            int id = allocP();
            if (id < 0) break;
            pX[id]=x; pY[id]=y; pZ[id]=z;
            float ang = rng.nextFloat()*(float)Math.PI*2;
            float sp = 400f + rng.nextFloat()*800f;
            pVX[id]=(float)Math.cos(ang)*sp; pVY[id]=(float)Math.sin(ang)*sp; pVZ[id]=rng.nextFloat()*600f;
            pLife[id]=pMax[id]=0.6f+rng.nextFloat()*0.6f;
            pR[id]=1f; pG[id]=0.9f; pB[id]=0.4f; pS[id]=25f+rng.nextFloat()*40f;
        }
    }

    private int allocP() {
        for (int i=0;i<PART;i++) if (pLife[i]<=0) { if (i>=pCount) pCount=i+1; return i; }
        return -1;
    }

    private void updateParticles(float dt) {
        for (int i=0;i<pCount;i++) {
            if (pLife[i]<=0) continue;
            pLife[i]-=dt;
            pX[i]+=pVX[i]*dt; pY[i]+=pVY[i]*dt; pZ[i]+=pVZ[i]*dt;
            pVZ[i]-=600f*dt;
            pVX[i]*=0.98f; pVY[i]*=0.98f;
        }
    }

    private void drawParticles() {
        for (int i=0;i<pCount;i++) {
            if (pLife[i]<=0) continue;
            float t=pLife[i]/Math.max(0.01f,pMax[i]);
            litBox(pX[i],pY[i],pZ[i], pS[i]*t,pS[i]*t,pS[i]*t, pR[i],pG[i],pB[i], t*0.8f, 0.5f, t*0.5f);
        }
    }

    // ========== DRAW HELPERS ==========
    private void litBox(float x,float y,float z, float sx,float sy,float sz,
                        float r,float g,float b,float a, float ambient, float emissive) {
        Matrix.setIdentityM(model,0);
        Matrix.translateM(model,0,x,y,z);
        Matrix.scaleM(model,0,sx,sy,sz);
        drawMesh(cubePN, 36, r,g,b,a, ambient, emissive);
    }

    private void ori(float x,float y,float z,
                     float fx,float fy,float fz, float ux,float uy,float uz, float rx,float ry,float rz,
                     float sx,float sy,float sz,
                     float r,float g,float b,float a, float ambient, float emissive) {
        Matrix.setIdentityM(model,0);
        model[0]=rx; model[1]=ry; model[2]=rz;
        model[4]=fx; model[5]=fy; model[6]=fz;
        model[8]=ux; model[9]=uy; model[10]=uz;
        model[12]=x; model[13]=y; model[14]=z;
        Matrix.setIdentityM(tmp2,0);
        Matrix.scaleM(tmp2,0, sx*0.5f, sy*0.5f, sz*0.5f);
        Matrix.multiplyMM(tmp,0, model,0, tmp2,0);
        System.arraycopy(tmp,0, model,0, 16);
        drawMesh(cubePN, 36, r,g,b,a, ambient, emissive);
    }

    private void litSphere(float x,float y,float z,float radius,
                           float r,float g,float b,float a, float ambient, float emissive) {
        Matrix.setIdentityM(model,0);
        Matrix.translateM(model,0,x,y,z);
        Matrix.scaleM(model,0,radius,radius,radius);
        drawMesh(spherePN, sphereVertexCount, r,g,b,a, ambient, emissive);
    }

    private void drawMesh(FloatBuffer buf, int verts, float r,float g,float b,float a, float ambient, float emissive) {
        Matrix.multiplyMM(tmp,0, view,0, model,0);
        Matrix.multiplyMM(mvp,0, proj,0, tmp,0);
        GLES20.glUniformMatrix4fv(uMVP,1,false,mvp,0);
        GLES20.glUniformMatrix4fv(uModel,1,false,model,0);
        GLES20.glUniform4f(uColor,r,g,b,a);
        GLES20.glUniform1f(uAmbient, ambient);
        GLES20.glUniform1f(uEmissive, emissive);
        buf.position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos,3,GLES20.GL_FLOAT,false,24,buf);
        buf.position(3);
        GLES20.glEnableVertexAttribArray(aNrm);
        GLES20.glVertexAttribPointer(aNrm,3,GLES20.GL_FLOAT,false,24,buf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,verts);
    }

    private static FloatBuffer buildCubePN() {
        // 6 faces, normals + positions
        float[] n = {
            0,0,1, 0,0,1, 0,0,1, 0,0,1, 0,0,1, 0,0,1,
            0,0,-1,0,0,-1,0,0,-1,0,0,-1,0,0,-1,0,0,-1,
            0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,
            0,-1,0,0,-1,0,0,-1,0,0,-1,0,0,-1,0,0,-1,0,
            1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,1,0,0,
            -1,0,0,-1,0,0,-1,0,0,-1,0,0,-1,0,0,-1,0,0
        };
        float[] p = {
            -1,-1,1, 1,-1,1, 1,1,1, -1,-1,1, 1,1,1, -1,1,1,
            -1,-1,-1, -1,1,-1, 1,1,-1, -1,-1,-1, 1,1,-1, 1,-1,-1,
            -1,1,-1, -1,1,1, 1,1,1, -1,1,-1, 1,1,1, 1,1,-1,
            -1,-1,-1, 1,-1,-1, 1,-1,1, -1,-1,-1, 1,-1,1, -1,-1,1,
            1,-1,-1, 1,1,-1, 1,1,1, 1,-1,-1, 1,1,1, 1,-1,1,
            -1,-1,-1, -1,-1,1, -1,1,1, -1,-1,-1, -1,1,1, -1,1,-1
        };
        float[] d = new float[36*6];
        for (int i=0;i<36;i++) {
            d[i*6]=p[i*3]; d[i*6+1]=p[i*3+1]; d[i*6+2]=p[i*3+2];
            d[i*6+3]=n[i*3]; d[i*6+4]=n[i*3+1]; d[i*6+5]=n[i*3+2];
        }
        FloatBuffer b=ByteBuffer.allocateDirect(d.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(d).position(0); return b;
    }

    private FloatBuffer buildSpherePN(int stacks, int slices) {
        float[] tmp = new float[stacks*slices*6*6];
        int idx=0;
        for (int i=0;i<stacks;i++) {
            float v0=i/(float)stacks, v1=(i+1)/(float)stacks;
            float phi0=(float)(Math.PI*(v0-0.5)), phi1=(float)(Math.PI*(v1-0.5));
            for (int j=0;j<slices;j++) {
                float u0=j/(float)slices, u1=(j+1)/(float)slices;
                float th0=(float)(2*Math.PI*u0), th1=(float)(2*Math.PI*u1);
                float[][] pts = {sph(phi0,th0),sph(phi0,th1),sph(phi1,th0),sph(phi1,th1)};
                // two tris, normal = position on unit sphere
                idx = putPN(tmp,idx,pts[0]); idx=putPN(tmp,idx,pts[2]); idx=putPN(tmp,idx,pts[3]);
                idx = putPN(tmp,idx,pts[0]); idx=putPN(tmp,idx,pts[3]); idx=putPN(tmp,idx,pts[1]);
            }
        }
        sphereVertexCount = idx/6;
        FloatBuffer b=ByteBuffer.allocateDirect(idx*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(tmp,0,idx).position(0); return b;
    }
    private static float[] sph(float phi, float th) {
        float cp=(float)Math.cos(phi), sp=(float)Math.sin(phi);
        float ct=(float)Math.cos(th), st=(float)Math.sin(th);
        return new float[]{cp*ct, cp*st, sp};
    }
    private static int putPN(float[] a, int i, float[] p) {
        a[i++]=p[0];a[i++]=p[1];a[i++]=p[2]; a[i++]=p[0];a[i++]=p[1];a[i++]=p[2]; return i;
    }

    private static int link(String vs, String fs) {
        int v=compile(GLES20.GL_VERTEX_SHADER,vs), f=compile(GLES20.GL_FRAGMENT_SHADER,fs);
        int p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,v); GLES20.glAttachShader(p,f); GLES20.glLinkProgram(p); return p;
    }
    private static int compile(int type, String src) {
        int s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,src); GLES20.glCompileShader(s); return s;
    }
    private void drawGlb(GlbModel glb, float x, float y, float z,
                         float fx, float fy, float fz,
                         float ux, float uy, float uz,
                         float rx, float ry, float rz,
                         float colorScale, float emissive) {
        Matrix.setIdentityM(model, 0);
        // local +X -> forward, +Y -> right, +Z -> up
        model[0] = fx; model[1] = fy; model[2] = fz;
        model[4] = rx; model[5] = ry; model[6] = rz;
        model[8] = ux; model[9] = uy; model[10] = uz;
        model[12] = x; model[13] = y; model[14] = z;
        float[] rot = new float[16];
        Matrix.setRotateM(rot, 0, 90f, 0f, 0f, 1f);
        float[] oriented = new float[16];
        Matrix.multiplyMM(oriented, 0, model, 0, rot, 0);
        System.arraycopy(oriented, 0, model, 0, 16);

        GLES20.glUseProgram(meshProgram);
        GLES20.glUniform3f(mULight, lightDir[0], lightDir[1], lightDir[2]);
        for (GlbModel.Primitive prim : glb.primitives) {
            Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
            GLES20.glUniformMatrix4fv(mUMVP, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(mUModel, 1, false, model, 0);
            GLES20.glUniform4f(mUColor, prim.baseColor[0]*colorScale, prim.baseColor[1]*colorScale,
                    prim.baseColor[2]*colorScale, prim.baseColor[3]);
            GLES20.glUniform1f(mUAmbient, 0.4f);
            GLES20.glUniform1f(mUEmissive, emissive);
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
        GLES20.glUseProgram(program);
    }

    private void drawGlbUniformScale(GlbModel glb, float x, float y, float z, float scale,
                                     float cr, float cg, float cb, float emissive) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        Matrix.scaleM(model, 0, scale, scale, scale);
        GLES20.glUseProgram(meshProgram);
        GLES20.glUniform3f(mULight, lightDir[0], lightDir[1], lightDir[2]);
        for (GlbModel.Primitive prim : glb.primitives) {
            Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
            GLES20.glUniformMatrix4fv(mUMVP, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(mUModel, 1, false, model, 0);
            GLES20.glUniform4f(mUColor, prim.baseColor[0]*cr, prim.baseColor[1]*cg, prim.baseColor[2]*cb, prim.baseColor[3]);
            GLES20.glUniform1f(mUAmbient, 0.45f);
            GLES20.glUniform1f(mUEmissive, emissive);
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
        GLES20.glUseProgram(program);
    }

    private void drawBillboard(float x, float y, float z, float size, int texId,
                               float r, float g, float b, float a) {
        if (texId == 0 || quadPN == null) return;
        float[] camR = {view[0], view[4], view[8]};
        float[] camU = {view[1], view[5], view[9]};
        Matrix.setIdentityM(model, 0);
        model[0] = camR[0]*size; model[1] = camR[1]*size; model[2] = camR[2]*size;
        model[4] = camU[0]*size; model[5] = camU[1]*size; model[6] = camU[2]*size;
        model[8] = 0; model[9] = 0; model[10] = 1;
        model[12] = x; model[13] = y; model[14] = z;

        GLES20.glDepthMask(false);
        GLES20.glUseProgram(spriteProgram);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
        GLES20.glUniformMatrix4fv(sUMVP, 1, false, mvp, 0);
        GLES20.glUniform4f(sUColor, r, g, b, a);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glUniform1i(sUTex, 0);
        quadPN.position(0);
        GLES20.glEnableVertexAttribArray(sAPos);
        GLES20.glVertexAttribPointer(sAPos, 3, GLES20.GL_FLOAT, false, 20, quadPN);
        quadPN.position(3);
        GLES20.glEnableVertexAttribArray(sAUv);
        GLES20.glVertexAttribPointer(sAUv, 2, GLES20.GL_FLOAT, false, 20, quadPN);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDepthMask(true);
        GLES20.glUseProgram(program);
    }

    private static FloatBuffer buildQuad() {
        float[] v = {
                -0.5f,-0.5f,0, 0,0,
                 0.5f,-0.5f,0, 1,0,
                 0.5f, 0.5f,0, 1,1,
                -0.5f,-0.5f,0, 0,0,
                 0.5f, 0.5f,0, 1,1,
                -0.5f, 0.5f,0, 0,1,
        };
        FloatBuffer b = ByteBuffer.allocateDirect(v.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

}
