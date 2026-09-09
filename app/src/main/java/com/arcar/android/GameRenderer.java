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
    private int mUCameraPos, mUEmissiveFactor, mUMetallic, mURoughness;
    private int spriteProgram;
    private int sAPos, sAUv, sUMVP, sUColor, sUTex;
    private FloatBuffer quadPN; // pos3+uv2 for billboards
    private FloatBuffer flatQuadPN; // pos3+nrm3 unit quad, for field surface/markings (Fix #5)
    private FloatBuffer centerRingPN; // pos3+nrm3 real center-circle ring mesh (Fix #6)
    private int centerRingVerts;
    private FloatBuffer cornerWallPN; // pos3+nrm3 quarter-cylinder corner fillet (Fix #8)
    private int cornerWallVerts;
    private String pendingCarId = "octane";
    private CarCatalog.VisualTransform currentCarVisual = CarCatalog.find("octane").visual;
    private final float[] currentCameraPos = new float[3];

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
    private int shadowTexId = 0;
    private volatile float steerInput = 0f;
    public void setSteerInput(float s) { steerInput = s; }
    private volatile float handbrakeInput = 0f;
    public void setHandbrakeInput(float h) { handbrakeInput = h; }
    private float goalFlash;
    private float impactFlash;
    private float ballTrailAcc;
    private final float[] bTrailX = new float[24], bTrailY = new float[24], bTrailZ = new float[24];
    private int bTrailN, bTrailH;

    private float smoothFov = 70f;
    // Fix #28 / #51 / #52 — game-feel state
    private boolean wasOnGround = true;
    private float landShake = 0f;
    private float impactShake = 0f;
    private float goalShake = 0f;
    private float boostShake = 0f;
    private float superStreak = 0f;
    // Fix #36 prealloc
    private final float[] corrM = new float[16];
    private final float[] offsetM = new float[16];
    private final float[] orientedM = new float[16];
    private final float[] snapCopy = new float[40];

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
        flatQuadPN = buildFlatQuadPN();
        centerRingVerts = 0; // built lazily below with actual field dimensions
        centerRingPN = buildRingPN(920f, 55f, 48);
        centerRingVerts = 48 * 6;
        cornerWallPN = buildCurvedWallPN(650f, 2200f, 10);
        cornerWallVerts = 10 * 6 * 2; // double-sided

        // Textured mesh shader — PBR-lite (Fix #14 / #16): metallic/roughness response,
        // a cheap Fresnel rim term, plus Reinhard tone mapping + gamma correction so the
        // vehicle no longer looks flat/too dark under the single directional light.
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
            // Roughness -> a crude specular lobe exponent (rougher = broader/dimmer highlight).
            "  float shininess = mix(128.0, 4.0, uRoughness);\n" +
            "  float specStrength = mix(0.06, 0.9, uMetallic);\n" +
            "  float spec = pow(ndh, shininess) * specStrength;\n" +
            // Simplified Fresnel-Schlick rim, stronger on metals.
            "  float fresnel = pow(1.0 - ndv, 5.0);\n" +
            "  float fresnelStrength = mix(0.04, 0.6, uMetallic);\n" +
            "  vec4 texC = (uUseTex > 0.5) ? texture2D(uTex, vUv) : vec4(1.0);\n" +
            "  vec3 baseCol = uColor.rgb * texC.rgb;\n" +
            // Metals tint their specular/fresnel with base color instead of white.
            "  vec3 specCol = mix(vec3(1.0), baseCol, uMetallic);\n" +
            "  float diffuseAmt = (1.0 - uMetallic);\n" +
            "  vec3 diffuse = baseCol * diffuseAmt * (uAmbient + (1.0 - uAmbient) * ndl);\n" +
            "  vec3 color = diffuse + specCol * spec + specCol * fresnel * fresnelStrength;\n" +
            "  color += (baseCol * uEmissive) + uEmissiveFactor;\n" +
            // Tone mapping (Reinhard) + gamma correction.
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
            currentCarVisual = CarCatalog.find(pendingCarId).visual;
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
            NativeBridge.nativeGetSnapshot(snapCopy);
            updateSnapshot(snapCopy);
        } catch (Throwable t) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            return;
        }

        // Fix #36: reuse snapCopy
        final float[] S = snapCopy;
        synchronized (snapLock) { System.arraycopy(snapshot, 0, S, 0, 40); }
        if (S[29] < 0.5f) return;

        float cx=S[0],cy=S[1],cz=S[2];
        float fx=S[3],fy=S[4],fz=S[5];
        float ux=S[6],uy=S[7],uz=S[8];
        float rx=S[9],ry=S[10],rz=S[11];
        float bx=S[12],by=S[13],bz=S[14], br=S[15]>1?S[15]:91.25f;
        float camx=S[16],camy=S[17],camz=S[18];
        currentCameraPos[0]=camx; currentCameraPos[1]=camy; currentCameraPos[2]=camz;
        float tx=S[19],ty=S[20],tz=S[21];
        float boost=S[22], speed=S[23];
        boolean ballCam=S[24]>0.5f, onGround=S[25]>0.5f, boosting=S[26]>0.5f, isSuper=S[27]>0.5f;
        float fov = S[30] > 1f ? S[30] : 70f;
        float impact = S[33];
        boolean goal = S[34] > 0.5f;
        float cvx=S[35],cvy=S[36],cvz=S[37];
        float ballSp = S[32];
        float nativeShake = S[31];

        if (hudListener != null) hudListener.onHud(boost, speed, ballCam, true, boosting, goal);

        float spd = (float)Math.sqrt(cvx*cvx+cvy*cvy+cvz*cvz);

        // Effects state
        if (impact > 300f) {
            spawnImpact(bx, by, bz, impact);
            impactFlash = Math.min(1f, impactFlash + impact / 2500f);
            impactShake = Math.min(1f, impactShake + impact / 2200f); // Fix #28
        }
        if (goal) {
            goalFlash = 1f;
            goalShake = 1f;
            spawnGoal(bx, by, bz);
        }
        goalFlash = Math.max(0f, goalFlash - dt * 0.7f);
        impactFlash = Math.max(0f, impactFlash - dt * 2.5f);

        // Fix #52 Landing effect: airborne -> grounded edge
        if (onGround && !wasOnGround && cz < 80f) {
            spawnLanding(cx, cy, 5f);
            landShake = 0.55f;
        }
        wasOnGround = onGround;

        // Fix #51 Tire skid when powersliding on ground with lateral speed
        float lat = Math.abs(cvx*rx + cvy*ry + cvz*rz);
        if (onGround && handbrakeInput > 0.4f && lat > 400f && spd > 500f) {
            spawnSkid(cx - fx*30f + rx*28f, cy - fy*30f + ry*28f, 4f);
            spawnSkid(cx - fx*30f - rx*28f, cy - fy*30f - ry*28f, 4f);
        }

        // Fix #20 Supersonic streak intensity
        if (isSuper) superStreak = Math.min(1f, superStreak + dt * 3f);
        else superStreak = Math.max(0f, superStreak - dt * 2f);

        // Fix #28 decay shakes
        landShake = Math.max(0f, landShake - dt * 3.5f);
        impactShake = Math.max(0f, impactShake - dt * 4f);
        goalShake = Math.max(0f, goalShake - dt * 1.8f);
        if (boosting) boostShake = Math.min(0.25f, boostShake + dt * 0.8f);
        else boostShake = Math.max(0f, boostShake - dt * 2f);

        updateParticles(dt);
        updateBoostTrail(dt, boosting || isSuper, cx - fx*45f, cy - fy*45f, cz - fz*15f + ux*5f);
        if (ballSp > 1500f) {
            ballTrailAcc += dt;
            if (ballTrailAcc > 0.025f) {
                ballTrailAcc = 0;
                bTrailX[bTrailH]=bx; bTrailY[bTrailH]=by; bTrailZ[bTrailH]=bz;
                bTrailH = (bTrailH+1)%24;
                if (bTrailN < 24) bTrailN++;
            }
        } else if (bTrailN > 0 && (ballTrailAcc += dt) > 0.05f) {
            ballTrailAcc = 0; bTrailN--;
        }

        wheelAngle += (spd * 0.02f) * dt * 60f;

        // FOV Fix #26: speed + boost + supersonic
        float targetFov = fov < 1f ? 70f : fov;
        targetFov += Math.min(12f, spd / 180f);
        if (boosting) targetFov += 4f;
        if (isSuper) targetFov += 3f;
        smoothFov += (targetFov - smoothFov) * 0.12f;

        // Camera shake (Fix #28) — apply small offsets to look-at
        float totalShake = Math.min(1.2f, landShake + impactShake + goalShake * 0.7f + boostShake + nativeShake * 0.01f);
        float shx = (rng.nextFloat() - 0.5f) * totalShake * 18f;
        float shy = (rng.nextFloat() - 0.5f) * totalShake * 18f;
        float shz = (rng.nextFloat() - 0.5f) * totalShake * 10f;

        float aspect = (float) width / Math.max(1, height);
        Matrix.perspectiveM(proj, 0, smoothFov, aspect, 6f, 30000f);
        Matrix.setLookAtM(view, 0, camx+shx, camy+shy, camz+shz, tx, ty, tz, 0, 0, 1);

        // Clear — stadium night sky + goal flash
        float flash = goalFlash * 0.35f + impactFlash * 0.15f;
        if (appCtx != null) {
            String want = CarCatalog.getSelectedId(appCtx);
            if (assets.car == null || !want.equals(pendingCarId)) {
                pendingCarId = want;
                assets.loadAll(appCtx, want);
            }
            currentCarVisual = CarCatalog.find(want).visual;
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
        // Ground shadow blobs — Fix #11/#12: soft circular texture-based shadows,
        // sized/faded by height, instead of a fixed black rectangular box.
        drawGroundShadow(cx, cy, cz, 95f, 900f);
        drawGroundShadow(bx, by, bz, br*1.1f, 900f);
    }

    // ========== STADIUM ==========
    private void drawStadium() {
        // Turf base — Fix #5: a real flat surface quad instead of a 4-unit-thick box.
        litFlatQuad(0,0,-2, 8200,10300, 0.12f,0.42f,0.18f, 1f, 0.55f, 0f);
        // Lighter turf stripes
        for (int i = -5; i <= 5; i++) {
            float yy = i * 900f;
            litFlatQuad(0, yy, -0.5f, 8000, 400, 0.14f,0.48f,0.20f, 1f, 0.55f, 0f);
        }
        // Lines (emissive white) — flat decals, offset slightly above the turf (Fix #42).
        litFlatQuad(0,0,0.5f, 35,10240, 0.95f,0.95f,0.9f, 1f, 0.7f, 0.15f);
        litFlatQuad(-4090,0,0.5f, 25,10240, 0.9f,0.9f,0.85f, 1f, 0.7f, 0.1f);
        litFlatQuad(4090,0,0.5f, 25,10240, 0.9f,0.9f,0.85f, 1f, 0.7f, 0.1f);
        litFlatQuad(0,-5120,0.5f, 8192, 25, 0.9f,0.9f,0.85f, 1f, 0.7f, 0.1f);
        litFlatQuad(0,5120,0.5f, 8192, 25, 0.9f,0.9f,0.85f, 1f, 0.7f, 0.1f);
        // Center circle — Fix #6: a real generated ring mesh instead of 32 boxes.
        litCenterRing(0,0,0.6f, 0.95f,0.95f,0.9f,1f,0.7f,0.12f);
        litFlatQuad(0,0,0.7f, 100,100, 0.95f,0.95f,0.9f,1f,0.7f,0.12f);

        // Side walls — translucent-ish darker
        litBox(-4110,0,1100, 60,10400,2200, 0.18f,0.22f,0.32f, 0.92f, 0.45f, 0.05f);
        litBox(4110,0,1100, 60,10400,2200, 0.18f,0.22f,0.32f, 0.92f, 0.45f, 0.05f);
        // Team ends
        litBox(0,-5240,1100, 8300,80,2200, 0.12f,0.22f,0.55f, 1f, 0.4f, 0.08f);
        litBox(0,5240,1100, 8300,80,2200, 0.55f,0.22f,0.12f, 1f, 0.4f, 0.08f);
        // Corner fillets — Fix #8: smooth curved transition between side walls and
        // end walls instead of the walls meeting in a hard rectangular corner.
        litCornerWall(4110-650, -5240+650, 180f, 0.18f,0.22f,0.32f, 0.92f, 0.45f, 0.05f);
        litCornerWall(4110-650, 5240-650, 90f, 0.18f,0.22f,0.32f, 0.92f, 0.45f, 0.05f);
        litCornerWall(-4110+650, -5240+650, 270f, 0.18f,0.22f,0.32f, 0.92f, 0.45f, 0.05f);
        litCornerWall(-4110+650, 5240-650, 0f, 0.18f,0.22f,0.32f, 0.92f, 0.45f, 0.05f);
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

    /**
     * Fix #9: an actual low-poly net lattice (thin alpha-blended strings on the back
     * panel and two side panels) instead of one solid translucent box, which used to
     * read as a flat colored wall rather than a net you can see through.
     */
    private void drawNet(float x, float y, float zBase, float halfW, float heightTop, float depth,
                         float r, float g, float b) {
        int strands = 9;
        float strandThickness = 6f;
        float netAlpha = 0.5f;
        // Back panel (in the XZ plane, facing along Y)
        float backY = y + depth;
        for (int i = 0; i <= strands; i++) {
            float fx = x - halfW + (2f*halfW) * i / strands;
            litBox(fx, backY, (zBase+heightTop)*0.5f, strandThickness, 20f, heightTop-zBase,
                    r,g,b, netAlpha, 0.4f, 0.08f);
        }
        int rows = 6;
        for (int i = 0; i <= rows; i++) {
            float fz = zBase + (heightTop-zBase) * i / rows;
            litBox(x, backY, fz, halfW*2f, 20f, strandThickness,
                    r,g,b, netAlpha, 0.4f, 0.08f);
        }
        // Side panels (in the YZ plane, taper from the goal mouth back to the back panel)
        for (int side = -1; side <= 1; side += 2) {
            float sx = x + side * halfW;
            for (int i = 0; i <= 5; i++) {
                float fy = y + depth * i / 5f;
                litBox(sx, fy, (zBase+heightTop)*0.5f, strandThickness, 20f, heightTop-zBase,
                        r,g,b, netAlpha, 0.4f, 0.08f);
            }
            for (int i = 0; i <= rows; i++) {
                float fz = zBase + (heightTop-zBase) * i / rows;
                litBox(sx, y+depth*0.5f, fz, 20f, depth, strandThickness,
                        r,g,b, netAlpha, 0.4f, 0.08f);
            }
        }
    }

    /**
     * Fix #18: proper layered boost exhaust — nozzle glow, white-hot core, orange
     * outer flame, a soft glow halo, trailing smoke wisps, and a few sparks —
     * instead of 1–3 flat billboards. `power` is 0..1 (ramps with speed/boost).
     */
    private void drawBoostFlame(float ex, float ey, float ez,
                                float fx, float fy, float fz,
                                float ux, float uy, float uz,
                                float rx, float ry, float rz, float power) {
        long t = System.nanoTime();
        float flick = 0.75f + 0.25f * (float)Math.sin(t * 1.4e-7);
        float flick2 = 0.8f + 0.2f * (float)Math.sin(t * 2.3e-7 + 1.7f);

        // 1) Nozzle glow — small bright disc right at the exhaust port.
        int flare = assets.tex("textures/particles/flare_01.png");
        if (flare != 0) drawBillboard(ex, ey, ez, 34f * flick, flare, 1f, 1f, 0.85f, 0.9f);

        // 2) White-hot core — short, tight, right behind the nozzle.
        int flameCore = assets.tex("textures/particles/flame_01.png");
        if (flameCore != 0) {
            drawBillboard(ex - fx*10, ey - fy*10, ez - fz*10, 30f * flick,
                    flameCore, 1f, 1f, 0.9f, 0.95f);
        }

        // 3) Outer flame — longer, orange, extends further back, scales with power.
        int flameOuter = assets.tex("textures/particles/flame_03.png");
        if (flameOuter != 0) {
            drawBillboard(ex - fx*30, ey - fy*30, ez - fz*30, (55f + 25f*power) * flick,
                    flameOuter, 1f, 0.65f, 0.25f, 0.85f);
            drawBillboard(ex - fx*55, ey - fy*55, ez - fz*55, (40f + 20f*power) * flick2,
                    assets.tex("textures/particles/flame_05.png"), 1f, 0.5f, 0.15f, 0.6f * power + 0.3f);
        } else {
            ori(ex,ey,ez, fx,fy,fz,ux,uy,uz,rx,ry,rz, 22, 55f*flick, 18, 1f,0.45f,0.05f,0.95f,0.5f,0.95f);
        }

        // 4) Soft glow halo around the whole plume, additive-feeling via low alpha.
        if (flare != 0) {
            drawBillboard(ex - fx*25, ey - fy*25, ez - fz*25, 90f + 30f*power,
                    flare, 1f, 0.55f, 0.2f, 0.22f);
        }

        // 5) Smoke wisps trailing off the back of the flame, faint and cool-toned.
        int smoke = assets.tex("textures/smoke/blackSmoke05.png");
        if (smoke != 0) {
            float sOff = 70f + 20f*(float)Math.sin(t*0.9e-7);
            drawBillboard(ex - fx*sOff, ey - fy*sOff, ez - fz*sOff + 6f, 45f, smoke, 0.5f, 0.5f, 0.55f, 0.18f);
        }

        // 6) A couple of stray sparks kicked off the flame edge.
        int spark = assets.tex("textures/particles/spark_02.png");
        if (spark != 0) {
            float jitter1 = (float)Math.sin(t*3.1e-7) * 14f;
            float jitter2 = (float)Math.cos(t*2.7e-7) * 14f;
            drawBillboard(ex - fx*40 + rx*jitter1, ey - fy*40 + ry*jitter1, ez - fz*40 + uz*4f,
                    10f, spark, 1f, 0.85f, 0.4f, 0.8f);
            drawBillboard(ex - fx*20 + rx*jitter2, ey - fy*20 + ry*jitter2, ez - fz*20 - uz*3f,
                    8f, spark, 1f, 0.9f, 0.5f, 0.7f);
        }
    }

    private void drawGoal(float x, float y, boolean blue) {
        float r=blue?0.25f:0.95f, g=blue?0.45f:0.4f, b=blue?1f:0.2f;
        // Posts + bar
        litBox(x-460,y,320, 35,35,640, r,g,b,1f,0.5f,0.25f);
        litBox(x+460,y,320, 35,35,640, r,g,b,1f,0.5f,0.25f);
        litBox(x,y,640, 960,35,35, r,g,b,1f,0.5f,0.25f);
        // Net — Fix #9: real lattice instead of one translucent box "wall".
        float depth = blue ? -220 : 220;
        drawNet(x, y, 0f, 440f, 620f, depth, r, g, b);
        // Goal mouth floor mark
        litBox(x, y+(blue?-80:80), 3, 900, 60, 2, 0.95f,0.95f,0.9f,1f,0.7f,0.1f);
        if (goalFlash > 0.05f) {
            litBox(x, y+depth*0.5f, 400, 1000, 500, 700, r,g,b, goalFlash*0.5f, 0.6f, goalFlash);
        }
    }

    /** Fix #53: pad base + emissive ring + glow (big vs small distinct). */
    private void drawBoostPads() {
        int flare = assets.tex("textures/particles/flare_01.png");
        float[][] big = {{-3072,-4096},{3072,-4096},{-3072,4096},{3072,4096},{-3584,0},{3584,0}};
        for (float[] p : big) {
            litBox(p[0],p[1],4, 220,220,8, 0.12f,0.1f,0.04f,1f,0.35f,0f);
            litBox(p[0],p[1],10, 180,180,6, 1f,0.65f,0.1f,1f,0.45f,0.35f); // ring
            litBox(p[0],p[1],16, 90,90,10, 1f,0.9f,0.35f,1f,0.5f,0.95f); // core
            if (flare != 0) drawBillboard(p[0], p[1], 28f, 140f, flare, 1f,0.75f,0.2f, 0.35f);
        }
        float[] ys = {-2480,-1024,1024,2480};
        for (float sy : ys) {
            for (float sx : new float[]{-1780,1780}) {
                litBox(sx,sy,3, 100,100,6, 0.15f,0.12f,0.05f,1f,0.4f,0f);
                litBox(sx,sy,8, 70,70,6, 1f,0.7f,0.2f,1f,0.5f,0.7f);
                if (flare != 0) drawBillboard(sx, sy, 18f, 70f, flare, 1f,0.8f,0.25f, 0.25f);
            }
        }
    }

    // ========== CAR ==========
    private void drawCar(float x,float y,float z, float fx,float fy,float fz,
                         float ux,float uy,float uz, float rx,float ry,float rz,
                         boolean boosting, boolean isSuper, boolean onGround, float speed) {
        if (assets.car != null && !assets.car.primitives.isEmpty()) {
            drawGlb(assets.car, x, y, z, fx, fy, fz, ux, uy, uz, rx, ry, rz,
                    isSuper ? 1.15f : 1f, isSuper ? 0.25f : 0.05f, currentCarVisual);
            // Boost exhaust — Fix #18: layered nozzle/core/outer/glow/smoke/sparks.
            float ex=x-fx*55, ey=y-fy*55, ez=z-fz*55;
            if (boosting || isSuper) {
                float power = 0.7f + Math.min(speed/2300f,1f)*0.5f + (isSuper?0.25f:0f);
                drawBoostFlame(ex, ey, ez, fx, fy, fz, ux, uy, uz, rx, ry, rz, power);
            }
            // Fix #20 supersonic streaks
            if (isSuper || superStreak > 0.05f)
                drawSupersonicStreaks(x,y,z, fx,fy,fz, rx,ry,rz, ux,uy,uz);
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

        // Wheels (Fix #47): front wheels now yaw with steer input, and all four wheels
        // spin around their axle with wheelAngle — previously wheelAngle was computed
        // but never applied, so wheels never visually turned or rolled.
        // wl entries: {forwardOffset, rightOffset, upOffset, isFrontWheel}
        float[][] wl = {{42,30,-10,1},{42,-30,-10,1},{-38,30,-10,0},{-38,-30,-10,0}};
        float steerRad = steerInput * 0.35f; // clamp visual steer angle (~20 deg max)
        for (float[] w : wl) {
            float wx=x+fx*w[0]+rx*w[1]+ux*w[2];
            float wy=y+fy*w[0]+ry*w[1]+uy*w[2];
            float wz=z+fz*w[0]+rz*w[1]+uz*w[2];
            boolean front = w[3] > 0.5f;
            float yaw = front ? steerRad : 0f;
            // Yaw the wheel's local forward/right around the car's up axis for steering.
            float cy = (float)Math.cos(yaw), sy = (float)Math.sin(yaw);
            float wfx = fx*cy + rx*sy, wfy = fy*cy + ry*sy, wfz = fz*cy + rz*sy;
            float wrx = rx*cy - fx*sy, wry = ry*cy - fy*sy, wrz = rz*cy - fz*sy;
            // Roll the wheel around its own axle (the right/lateral axis) for rolling motion.
            float cr = (float)Math.cos(wheelAngle), sr = (float)Math.sin(wheelAngle);
            float wux = ux*cr - wfx*sr, wuy = uy*cr - wfy*sr, wuz = uz*cr - wfz*sr;
            float wfx2 = wfx*cr + ux*sr, wfy2 = wfy*cr + uy*sr, wfz2 = wfz*cr + uz*sr;
            ori(wx,wy,wz, wfx2,wfy2,wfz2, wux,wuy,wuz, wrx,wry,wrz, 12,20,12, 0.08f,0.08f,0.08f,1f,0.3f,0f);
            ori(wx,wy,wz, wfx2,wfy2,wfz2, wux,wuy,wuz, wrx,wry,wrz, 6,14,6, 0.4f,0.4f,0.45f,1f,0.5f,0.1f);
        }

        // Boost exhaust — Fix #18: layered nozzle/core/outer/glow/smoke/sparks.
        float ex=x-fx*58, ey=y-fy*58, ez=z-fz*58;
        if (boosting || isSuper) {
            float power = 0.7f + Math.min(speed/2300f,1f)*0.5f + (isSuper?0.25f:0f);
            drawBoostFlame(ex, ey, ez, fx, fy, fz, ux, uy, uz, rx, ry, rz, power);
        } else {
            ori(ex,ey,ez, fx,fy,fz,ux,uy,uz,rx,ry,rz, 16,14,12, 0.2f,0.2f,0.25f,1f,0.4f,0f);
        }
        if (isSuper || superStreak > 0.05f)
            drawSupersonicStreaks(x,y,z, fx,fy,fz, rx,ry,rz, ux,uy,uz);
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

    /**
     * Fix #11 / #12: soft circular ground shadow (billboarded flat on the XY plane,
     * alpha-blended, using the circle_0x particle textures) replacing the old
     * `litBox(...)` black rectangular shadow. Size/opacity shrink with height so it
     * reads as "car/ball lifting off the ground" instead of a fixed dark box.
     */
    private void drawGroundShadow(float x, float y, float height, float baseRadius, float maxHeight) {
        int tex = shadowTexId != 0 ? shadowTexId : assets.tex("textures/particles/circle_01.png");
        if (tex == 0) tex = assets.tex("textures/particles/circle_05.png");
        shadowTexId = tex;
        if (tex == 0) return; // no shadow texture available — skip rather than draw a box
        float t = Math.min(Math.max(height, 0f) / Math.max(1f, maxHeight), 1f);
        float size = baseRadius * (1f - t * 0.45f); // shrinks a bit as it rises
        float alpha = 0.45f * (1f - t * 0.75f);     // dims as it rises
        if (alpha <= 0.02f) return;

        Matrix.setIdentityM(model, 0);
        model[0] = size; model[1] = 0; model[2] = 0;
        model[4] = 0; model[5] = size; model[6] = 0;
        model[8] = 0; model[9] = 0; model[10] = 1;
        model[12] = x; model[13] = y; model[14] = 3f; // just above the turf, avoids z-fighting

        GLES20.glDepthMask(false);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(spriteProgram);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
        GLES20.glUniformMatrix4fv(sUMVP, 1, false, mvp, 0);
        GLES20.glUniform4f(sUColor, 0f, 0f, 0f, alpha);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
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

    // (old box-based drawShadow removed — see drawGroundShadow above, Fix #11/#12)

    // ========== VFX ==========
    /**
     * Fix #19: continuous boost trail — previously sampled once per ~16ms regardless
     * of how far the car moved, so at high speed consecutive puffs were spaced far
     * apart and the "ribbon" had visible gaps. Now interpolates extra points when the
     * car has moved more than a step distance since the last sample, so the trail
     * stays unbroken at any speed, plus a slightly higher base spawn rate.
     */
    private final float[] lastTrailPos = new float[3];
    private boolean lastTrailValid = false;
    private void updateBoostTrail(float dt, boolean on, float x,float y,float z) {
        if (on) {
            tAcc += dt;
            if (tAcc > 0.012f) {
                tAcc = 0;
                if (lastTrailValid) {
                    float dx=x-lastTrailPos[0], dy=y-lastTrailPos[1], dz=z-lastTrailPos[2];
                    float dist=(float)Math.sqrt(dx*dx+dy*dy+dz*dz);
                    float step = 26f; // max gap between trail puffs, world units
                    int extra = Math.min((int)(dist/step), TRAIL - 1);
                    for (int k = 1; k <= extra; k++) {
                        float f = (float)k/(extra+1);
                        tX[tHead]=lastTrailPos[0]+dx*f; tY[tHead]=lastTrailPos[1]+dy*f; tZ[tHead]=lastTrailPos[2]+dz*f;
                        tLife[tHead]=1f;
                        tHead=(tHead+1)%TRAIL;
                    }
                }
                tX[tHead]=x; tY[tHead]=y; tZ[tHead]=z;
                tLife[tHead]=1f;
                tHead=(tHead+1)%TRAIL;
                lastTrailPos[0]=x; lastTrailPos[1]=y; lastTrailPos[2]=z;
                lastTrailValid = true;
            }
        } else {
            lastTrailValid = false;
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
            // Fix #19: cool the color from white-hot near the nozzle to deep orange/red
            // as it ages, instead of a flat orange tint the whole way, for a smoother
            // continuous-ribbon read rather than a chain of identical puffs.
            float rC = 1f;
            float gC = 0.25f + 0.65f*life;
            float bC = 0.08f + 0.35f*life*life;
            if (flame != 0) {
                drawBillboard(tX[i], tY[i], tZ[i], s, flame, rC, gC, bC, life*0.85f);
            } else {
                litBox(tX[i],tY[i],tZ[i], s,s,s*0.7f, rC,gC,bC, life*0.7f, 0.5f, life);
            }
            if (life < 0.45f && smoke != 0) {
                drawBillboard(tX[i], tY[i], tZ[i]+8f, s*1.2f, smoke, 0.6f, 0.6f, 0.6f, life*0.35f);
            }
        }
    }

    /** Fix #21 soft ball trail */
    private void drawBallTrail() {
        int flare = assets.tex("textures/particles/flare_01.png");
        int circle = assets.tex("textures/particles/circle_01.png");
        for (int i=0;i<bTrailN;i++) {
            int idx=(bTrailH-1-i+48)%24;
            float t=1f-i/24f;
            float size = 35f + 50f * t;
            float a = t * 0.5f;
            if (flare != 0)
                drawBillboard(bTrailX[idx], bTrailY[idx], bTrailZ[idx], size*1.5f, flare, 1f,0.7f,0.25f, a*0.55f);
            if (circle != 0)
                drawBillboard(bTrailX[idx], bTrailY[idx], bTrailZ[idx], size, circle, 1f,0.9f,0.4f, a);
            else
                litSphere(bTrailX[idx],bTrailY[idx],bTrailZ[idx], size*0.55f, 1f,0.85f,0.3f, a, 0.5f, a*0.4f);
        }
    }

    /** Fix #22 impact sparks scaled by impulse */
    private void spawnImpact(float x,float y,float z, float impulse) {
        int n = Math.min(28, 10 + (int)(impulse/180f));
        for (int i=0;i<n;i++) {
            int id = allocP();
            if (id < 0) break;
            pX[id]=x; pY[id]=y; pZ[id]=z;
            float ang = rng.nextFloat()*(float)Math.PI*2;
            float sp = 250f + rng.nextFloat()*impulse*0.5f;
            pVX[id]=(float)Math.cos(ang)*sp; pVY[id]=(float)Math.sin(ang)*sp; pVZ[id]=150f+rng.nextFloat()*400f;
            pLife[id]=pMax[id]=0.28f+rng.nextFloat()*0.4f;
            pR[id]=1f; pG[id]=0.75f+rng.nextFloat()*0.25f; pB[id]=0.2f+rng.nextFloat()*0.3f;
            pS[id]=18f+rng.nextFloat()*35f;
        }
    }

    /** Fix #23 substantial goal explosion */
    private void spawnGoal(float x,float y,float z) {
        for (int i=0;i<55;i++) {
            int id = allocP();
            if (id < 0) break;
            pX[id]=x; pY[id]=y; pZ[id]=z;
            float ang = rng.nextFloat()*(float)Math.PI*2;
            float elev = rng.nextFloat()*(float)Math.PI*0.45f;
            float sp = 500f + rng.nextFloat()*1100f;
            pVX[id]=(float)(Math.cos(ang)*Math.cos(elev)*sp);
            pVY[id]=(float)(Math.sin(ang)*Math.cos(elev)*sp);
            pVZ[id]=(float)(Math.sin(elev)*sp) + 200f;
            pLife[id]=pMax[id]=0.7f+rng.nextFloat()*0.9f;
            // team-colored-ish warm explosion
            pR[id]=1f; pG[id]=0.55f+rng.nextFloat()*0.4f; pB[id]=0.15f+rng.nextFloat()*0.35f;
            pS[id]=30f+rng.nextFloat()*55f;
        }
        // extra smoke layer
        for (int i=0;i<18;i++) {
            int id = allocP();
            if (id < 0) break;
            pX[id]=x; pY[id]=y; pZ[id]=z+20f;
            float ang = rng.nextFloat()*(float)Math.PI*2;
            float sp = 80f + rng.nextFloat()*200f;
            pVX[id]=(float)Math.cos(ang)*sp; pVY[id]=(float)Math.sin(ang)*sp; pVZ[id]=40f+rng.nextFloat()*120f;
            pLife[id]=pMax[id]=1.0f+rng.nextFloat()*0.8f;
            pR[id]=0.55f; pG[id]=0.55f; pB[id]=0.6f; pS[id]=50f+rng.nextFloat()*40f;
        }
    }

    /** Fix #52 landing dust burst */
    private void spawnLanding(float x, float y, float z) {
        for (int i=0;i<14;i++) {
            int id = allocP();
            if (id < 0) break;
            pX[id]=x; pY[id]=y; pZ[id]=z;
            float ang = rng.nextFloat()*(float)Math.PI*2;
            float sp = 60f + rng.nextFloat()*180f;
            pVX[id]=(float)Math.cos(ang)*sp; pVY[id]=(float)Math.sin(ang)*sp; pVZ[id]=30f+rng.nextFloat()*80f;
            pLife[id]=pMax[id]=0.35f+rng.nextFloat()*0.35f;
            pR[id]=0.65f; pG[id]=0.6f; pB[id]=0.5f; pS[id]=25f+rng.nextFloat()*30f;
        }
    }

    /** Fix #51 tire skid smoke */
    private void spawnSkid(float x, float y, float z) {
        if (rng.nextFloat() > 0.35f) return; // throttle spawn rate
        int id = allocP();
        if (id < 0) return;
        pX[id]=x; pY[id]=y; pZ[id]=z;
        pVX[id]=(rng.nextFloat()-0.5f)*40f; pVY[id]=(rng.nextFloat()-0.5f)*40f; pVZ[id]=20f+rng.nextFloat()*40f;
        pLife[id]=pMax[id]=0.4f+rng.nextFloat()*0.3f;
        pR[id]=0.45f; pG[id]=0.45f; pB[id]=0.48f; pS[id]=22f+rng.nextFloat()*18f;
    }

    /** Fix #20 supersonic speed streaks (subtle, non-obscuring) */
    private void drawSupersonicStreaks(float x,float y,float z, float fx,float fy,float fz,
                                      float rx,float ry,float rz, float ux,float uy,float uz) {
        if (superStreak < 0.05f) return;
        int flare = assets.tex("textures/particles/flare_01.png");
        float a = superStreak * 0.35f;
        for (int i=0;i<5;i++) {
            float side = (i-2) * 18f;
            float back = 40f + i*12f;
            float px = x - fx*back + rx*side + ux*8f;
            float py = y - fy*back + ry*side + uy*8f;
            float pz = z - fz*back + rz*side + uz*8f;
            float sz = 50f + 30f*superStreak;
            if (flare != 0)
                drawBillboard(px,py,pz, sz, flare, 0.7f,0.85f,1f, a*(1f-i*0.12f));
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
        int spark = assets.tex("textures/particles/spark_02.png");
        int flare = assets.tex("textures/particles/flare_01.png");
        int smoke = assets.tex("textures/smoke/blackSmoke05.png");
        for (int i=0;i<pCount;i++) {
            if (pLife[i]<=0) continue;
            float t=pLife[i]/Math.max(0.01f,pMax[i]);
            float size = pS[i]*t;
            // cooler smoke-ish particles use smoke texture; hot ones use spark/flare
            boolean cool = pR[i] < 0.7f && pG[i] < 0.7f;
            if (cool && smoke != 0) {
                drawBillboard(pX[i],pY[i],pZ[i], size*1.8f, smoke, pR[i],pG[i],pB[i], t*0.55f);
            } else if (spark != 0) {
                drawBillboard(pX[i],pY[i],pZ[i], size*1.5f, spark, pR[i],pG[i],pB[i], t*0.9f);
            } else if (flare != 0) {
                drawBillboard(pX[i],pY[i],pZ[i], size*1.8f, flare, pR[i],pG[i],pB[i], t*0.7f);
            } else {
                litBox(pX[i],pY[i],pZ[i], size,size,size, pR[i],pG[i],pB[i], t*0.8f, 0.5f, t*0.5f);
            }
        }
    }

    // ========== DRAW HELPERS ==========
    private void litFlatQuad(float x,float y,float z, float sx,float sy,
                             float r,float g,float b,float a, float ambient, float emissive) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        Matrix.scaleM(model, 0, sx, sy, 1f);
        drawMesh(flatQuadPN, 6, r,g,b,a, ambient, emissive);
    }

    private void litCenterRing(float x, float y, float z,
                               float r, float g, float b, float a, float ambient, float emissive) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        drawMesh(centerRingPN, centerRingVerts, r,g,b,a, ambient, emissive);
    }

    /** Fix #5: a real flat surface quad (pos+normal) instead of a very thin litBox. */
    private static FloatBuffer buildFlatQuadPN() {
        float[] v = {
            -0.5f,-0.5f,0f, 0,0,1,
             0.5f,-0.5f,0f, 0,0,1,
             0.5f, 0.5f,0f, 0,0,1,
            -0.5f,-0.5f,0f, 0,0,1,
             0.5f, 0.5f,0f, 0,0,1,
            -0.5f, 0.5f,0f, 0,0,1,
        };
        FloatBuffer fb = ByteBuffer.allocateDirect(v.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(v).position(0);
        return fb;
    }

    /**
     * Fix #6: a real circular ring mesh (annulus, triangle list) for the center
     * circle, replacing the old "32 individual boxes" approximation. Flat, sits
     * slightly above the field, and follows a true circle rather than a segmented
     * polygon of blocky pieces.
     */
    private static FloatBuffer buildRingPN(float radius, float thickness, int segments) {
        float rOuter = radius + thickness * 0.5f;
        float rInner = radius - thickness * 0.5f;
        float[] v = new float[segments * 6 * 6]; // 6 verts/segment * 6 floats/vert
        int o = 0;
        for (int i = 0; i < segments; i++) {
            float a0 = (float)(i * Math.PI * 2 / segments);
            float a1 = (float)((i + 1) * Math.PI * 2 / segments);
            float ox0 = (float)Math.cos(a0)*rOuter, oy0 = (float)Math.sin(a0)*rOuter;
            float ix0 = (float)Math.cos(a0)*rInner, iy0 = (float)Math.sin(a0)*rInner;
            float ox1 = (float)Math.cos(a1)*rOuter, oy1 = (float)Math.sin(a1)*rOuter;
            float ix1 = (float)Math.cos(a1)*rInner, iy1 = (float)Math.sin(a1)*rInner;
            // two triangles per segment, both facing +Z
            float[] tri = {
                ix0,iy0,0f, 0,0,1,  ox0,oy0,0f, 0,0,1,  ox1,oy1,0f, 0,0,1,
                ix0,iy0,0f, 0,0,1,  ox1,oy1,0f, 0,0,1,  ix1,iy1,0f, 0,0,1,
            };
            System.arraycopy(tri, 0, v, o, tri.length);
            o += tri.length;
        }
        FloatBuffer fb = ByteBuffer.allocateDirect(v.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(v).position(0);
        return fb;
    }

    /**
     * Fix #8: a curved quarter-cylinder fillet mesh used at each of the 4 arena
     * corners, so the transition from side wall to end wall is a smooth curve
     * ("floor ─────╮ │ wall") instead of a hard 90-degree box intersection.
     * Built double-sided (both triangle windings) since the 4 corner instances
     * are mirrored/rotated per-corner and a single winding wouldn't stay
     * front-facing in every orientation.
     */
    private static FloatBuffer buildCurvedWallPN(float radius, float height, int segments) {
        float[] v = new float[segments * 6 * 2 * 6]; // segments * 2 tris * 2 sides * 6 floats
        int o = 0;
        for (int i = 0; i < segments; i++) {
            float a0 = (float)(Math.PI/2 * i / segments);
            float a1 = (float)(Math.PI/2 * (i+1) / segments);
            float x0 = (float)Math.cos(a0)*radius, y0 = (float)Math.sin(a0)*radius;
            float x1 = (float)Math.cos(a1)*radius, y1 = (float)Math.sin(a1)*radius;
            float nx0 = (float)Math.cos(a0), ny0 = (float)Math.sin(a0);
            float nx1 = (float)Math.cos(a1), ny1 = (float)Math.sin(a1);
            float[][] windings = {
                { // front winding
                    x0,y0,0, nx0,ny0,0,   x1,y1,0, nx1,ny1,0,   x1,y1,height, nx1,ny1,0,
                    x0,y0,0, nx0,ny0,0,   x1,y1,height, nx1,ny1,0,   x0,y0,height, nx0,ny0,0,
                },
                { // reversed winding (so it's visible regardless of front-face direction)
                    x1,y1,0, nx1,ny1,0,   x0,y0,0, nx0,ny0,0,   x0,y0,height, nx0,ny0,0,
                    x1,y1,0, nx1,ny1,0,   x0,y0,height, nx0,ny0,0,   x1,y1,height, nx1,ny1,0,
                }
            };
            for (float[] tri : windings) {
                System.arraycopy(tri, 0, v, o, tri.length);
                o += tri.length;
            }
        }
        FloatBuffer fb = ByteBuffer.allocateDirect(v.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(v).position(0);
        return fb;
    }

    /** Draws the curved corner fillet at (cx,cy), rotated so its arc faces the field. */
    private void litCornerWall(float cx, float cy, float rotDeg,
                               float r, float g, float b, float a, float ambient, float emissive) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, cx, cy, 0f);
        Matrix.rotateM(model, 0, rotDeg, 0f, 0f, 1f);
        drawMesh(cornerWallPN, cornerWallVerts, r,g,b,a, ambient, emissive);
    }

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
        drawGlb(glb, x, y, z, fx, fy, fz, ux, uy, uz, rx, ry, rz, colorScale, emissive, null);
    }

    /**
     * Fix #1 / #4 / #34 / #48: the physics basis (forward/right/up + position) is the
     * source of truth and is NEVER modified here. `visual` is an optional, per-vehicle
     * correction (see CarCatalog.VisualTransform) that only adjusts how the *mesh* is
     * presented on top of that physics basis — e.g. because the GLB's authored local
     * forward axis doesn't match the engine's forward convention. There is no longer
     * a single hardcoded rotation applied to every model.
     */
    private void drawGlb(GlbModel glb, float x, float y, float z,
                         float fx, float fy, float fz,
                         float ux, float uy, float uz,
                         float rx, float ry, float rz,
                         float colorScale, float emissive,
                         CarCatalog.VisualTransform visual) {
        Matrix.setIdentityM(model, 0);
        // Physics basis: local +X -> forward, +Y -> right, +Z -> up
        model[0] = fx; model[1] = fy; model[2] = fz;
        model[4] = rx; model[5] = ry; model[6] = rz;
        model[8] = ux; model[9] = uy; model[10] = uz;
        model[12] = x; model[13] = y; model[14] = z;

        if (visual != null) {
            // Fix #36 reuse preallocated matrices
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
            GLES20.glUniform4f(mUColor, prim.baseColor[0]*colorScale, prim.baseColor[1]*colorScale,
                    prim.baseColor[2]*colorScale, prim.baseColor[3]);
            GLES20.glUniform1f(mUAmbient, 0.4f);
            GLES20.glUniform1f(mUEmissive, emissive);
            GLES20.glUniform3f(mUEmissiveFactor, prim.emissiveFactor[0], prim.emissiveFactor[1], prim.emissiveFactor[2]);
            GLES20.glUniform1f(mUMetallic, prim.metallic);
            GLES20.glUniform1f(mURoughness, prim.roughness);
            // Fix #44: don't render every material the same way — alpha-tested/blended
            // materials (glass, decals) need different GL state than opaque paint.
            if ("BLEND".equals(prim.alphaMode)) {
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                GLES20.glDepthMask(false);
            } else {
                GLES20.glDisable(GLES20.GL_BLEND);
                GLES20.glDepthMask(true);
            }
            if (prim.doubleSided) {
                GLES20.glDisable(GLES20.GL_CULL_FACE);
            } else {
                GLES20.glEnable(GLES20.GL_CULL_FACE);
            }
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
        // Restore default GL state so subsequent draws (field, stadium, etc.) aren't
        // affected by a per-material BLEND/double-sided toggle made above.
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDepthMask(true);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glUseProgram(program);
    }

    private void drawGlbUniformScale(GlbModel glb, float x, float y, float z, float scale,
                                     float cr, float cg, float cb, float emissive) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        Matrix.scaleM(model, 0, scale, scale, scale);
        GLES20.glUseProgram(meshProgram);
        GLES20.glUniform3f(mULight, lightDir[0], lightDir[1], lightDir[2]);
        GLES20.glUniform3f(mUCameraPos, currentCameraPos[0], currentCameraPos[1], currentCameraPos[2]);
        for (GlbModel.Primitive prim : glb.primitives) {
            Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
            Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0);
            GLES20.glUniformMatrix4fv(mUMVP, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(mUModel, 1, false, model, 0);
            GLES20.glUniform4f(mUColor, prim.baseColor[0]*cr, prim.baseColor[1]*cg, prim.baseColor[2]*cb, prim.baseColor[3]);
            GLES20.glUniform1f(mUAmbient, 0.45f);
            GLES20.glUniform1f(mUEmissive, emissive);
            GLES20.glUniform3f(mUEmissiveFactor, prim.emissiveFactor[0], prim.emissiveFactor[1], prim.emissiveFactor[2]);
            GLES20.glUniform1f(mUMetallic, prim.metallic);
            GLES20.glUniform1f(mURoughness, prim.roughness);
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
