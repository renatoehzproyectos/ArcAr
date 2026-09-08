package com.arcar.android;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal binary glTF (GLB) loader for GLES2: positions, normals, UVs, indices, baseColor textures.
 * Applies node hierarchy at load time and normalizes to a target size.
 */
public class GlbModel {
    private static final String TAG = "GlbModel";

    public static final class Primitive {
        public FloatBuffer interleaved; // pos3 + nrm3 + uv2
        public int vertexCount;
        public int indexCount;
        public int indexType; // GL_UNSIGNED_SHORT or GL_UNSIGNED_INT
        public ByteBuffer indices;
        public int textureId; // 0 = none
        public float[] baseColor = {1, 1, 1, 1};
    }

    public final List<Primitive> primitives = new ArrayList<>();
    public float radius; // bounding sphere after normalize

    public static GlbModel load(AssetManager am, String assetPath, float targetMaxExtent) {
        try {
            byte[] data = readAll(am.open(assetPath));
            return parse(data, targetMaxExtent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load " + assetPath, e);
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static GlbModel parse(byte[] data, float targetMaxExtent) throws Exception {
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt();
        int version = bb.getInt();
        int length = bb.getInt();
        if (magic != 0x46546C67) throw new IllegalArgumentException("Not GLB");

        JSONObject json = null;
        ByteBuffer bin = null;
        while (bb.position() < length) {
            int chunkLen = bb.getInt();
            int chunkType = bb.getInt();
            byte[] chunk = new byte[chunkLen];
            bb.get(chunk);
            // pad
            int pad = (4 - (chunkLen % 4)) % 4;
            bb.position(bb.position() + pad);
            if (chunkType == 0x4E4F534A) { // JSON
                json = new JSONObject(new String(chunk, "UTF-8"));
            } else if (chunkType == 0x004E4942) { // BIN
                bin = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
            }
        }
        if (json == null || bin == null) throw new IllegalArgumentException("Missing JSON/BIN");

        // Load images → GL textures (must be called on GL thread)
        List<byte[]> imageBytes = new ArrayList<>();
        JSONArray images = json.optJSONArray("images");
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                JSONObject img = images.getJSONObject(i);
                if (img.has("bufferView")) {
                    JSONObject bv = json.getJSONArray("bufferViews").getJSONObject(img.getInt("bufferView"));
                    int off = bv.optInt("byteOffset", 0);
                    int len = bv.getInt("byteLength");
                    byte[] png = new byte[len];
                    ByteBuffer tmp = bin.duplicate();
                    tmp.position(off);
                    tmp.get(png);
                    imageBytes.add(png);
                } else {
                    imageBytes.add(null);
                }
            }
        }

        // Build node world matrices
        JSONArray nodes = json.optJSONArray("nodes");
        int nodeCount = nodes != null ? nodes.length() : 0;
        float[][] world = new float[nodeCount][16];
        boolean[] computed = new boolean[nodeCount];
        for (int i = 0; i < nodeCount; i++) Matrix.setIdentityM(world[i], 0);

        // local matrices
        float[][] local = new float[nodeCount][16];
        for (int i = 0; i < nodeCount; i++) {
            Matrix.setIdentityM(local[i], 0);
            JSONObject n = nodes.getJSONObject(i);
            if (n.has("matrix")) {
                JSONArray m = n.getJSONArray("matrix");
                for (int k = 0; k < 16; k++) local[i][k] = (float) m.getDouble(k);
            } else {
                float[] T = new float[16]; Matrix.setIdentityM(T, 0);
                float[] R = new float[16]; Matrix.setIdentityM(R, 0);
                float[] S = new float[16]; Matrix.setIdentityM(S, 0);
                if (n.has("translation")) {
                    JSONArray t = n.getJSONArray("translation");
                    Matrix.translateM(T, 0, (float)t.getDouble(0), (float)t.getDouble(1), (float)t.getDouble(2));
                }
                if (n.has("scale")) {
                    JSONArray s = n.getJSONArray("scale");
                    Matrix.scaleM(S, 0, (float)s.getDouble(0), (float)s.getDouble(1), (float)s.getDouble(2));
                }
                if (n.has("rotation")) {
                    JSONArray q = n.getJSONArray("rotation");
                    quatToMatrix(R, (float)q.getDouble(0), (float)q.getDouble(1), (float)q.getDouble(2), (float)q.getDouble(3));
                }
                float[] RS = new float[16];
                Matrix.multiplyMM(RS, 0, R, 0, S, 0);
                Matrix.multiplyMM(local[i], 0, T, 0, RS, 0);
            }
        }

        // parent links
        int[] parent = new int[nodeCount];
        for (int i = 0; i < nodeCount; i++) parent[i] = -1;
        for (int i = 0; i < nodeCount; i++) {
            JSONObject n = nodes.getJSONObject(i);
            if (n.has("children")) {
                JSONArray ch = n.getJSONArray("children");
                for (int c = 0; c < ch.length(); c++) parent[ch.getInt(c)] = i;
            }
        }
        float[] tmpM = new float[16];
        for (int i = 0; i < nodeCount; i++) {
            computeWorld(i, parent, local, world, computed, tmpM);
        }

        GlbModel model = new GlbModel();
        JSONArray meshes = json.getJSONArray("meshes");
        JSONArray accessors = json.getJSONArray("accessors");
        JSONArray bufferViews = json.getJSONArray("bufferViews");
        JSONArray materials = json.optJSONArray("materials");
        JSONArray textures = json.optJSONArray("textures");

        List<float[]> allPos = new ArrayList<>();

        for (int ni = 0; ni < nodeCount; ni++) {
            JSONObject n = nodes.getJSONObject(ni);
            if (!n.has("mesh")) continue;
            int meshIndex = n.getInt("mesh");
            JSONObject mesh = meshes.getJSONObject(meshIndex);
            JSONArray prims = mesh.getJSONArray("primitives");
            float[] M = world[ni];

            for (int pi = 0; pi < prims.length(); pi++) {
                JSONObject prim = prims.getJSONObject(pi);
                JSONObject attrs = prim.getJSONObject("attributes");
                if (!attrs.has("POSITION")) continue;

                float[] positions = readFloatVec(bin, accessors, bufferViews, attrs.getInt("POSITION"), 3);
                float[] normals = attrs.has("NORMAL")
                        ? readFloatVec(bin, accessors, bufferViews, attrs.getInt("NORMAL"), 3)
                        : null;
                float[] uvs = attrs.has("TEXCOORD_0")
                        ? readFloatVec(bin, accessors, bufferViews, attrs.getInt("TEXCOORD_0"), 2)
                        : null;
                int[] indices = prim.has("indices")
                        ? readIndices(bin, accessors, bufferViews, prim.getInt("indices"))
                        : sequential(positions.length / 3);

                int vertCount = positions.length / 3;
                float[] inter = new float[vertCount * 8];
                float[] nrmMat = new float[16];
                // normal matrix ≈ upper 3x3 of M (ignore translate)
                System.arraycopy(M, 0, nrmMat, 0, 16);
                nrmMat[12] = nrmMat[13] = nrmMat[14] = 0;

                for (int v = 0; v < vertCount; v++) {
                    float x = positions[v * 3], y = positions[v * 3 + 1], z = positions[v * 3 + 2];
                    float wx = M[0]*x + M[4]*y + M[8]*z + M[12];
                    float wy = M[1]*x + M[5]*y + M[9]*z + M[13];
                    float wz = M[2]*x + M[6]*y + M[10]*z + M[14];
                    inter[v * 8] = wx;
                    inter[v * 8 + 1] = wy;
                    inter[v * 8 + 2] = wz;
                    allPos.add(new float[]{wx, wy, wz});

                    float nx = 0, ny = 1, nz = 0;
                    if (normals != null) {
                        nx = normals[v * 3]; ny = normals[v * 3 + 1]; nz = normals[v * 3 + 2];
                        float nnx = nrmMat[0]*nx + nrmMat[4]*ny + nrmMat[8]*nz;
                        float nny = nrmMat[1]*nx + nrmMat[5]*ny + nrmMat[9]*nz;
                        float nnz = nrmMat[2]*nx + nrmMat[6]*ny + nrmMat[10]*nz;
                        float len = (float) Math.sqrt(nnx*nnx + nny*nny + nnz*nnz);
                        if (len > 1e-8f) { nnx /= len; nny /= len; nnz /= len; }
                        nx = nnx; ny = nny; nz = nnz;
                    }
                    inter[v * 8 + 3] = nx;
                    inter[v * 8 + 4] = ny;
                    inter[v * 8 + 5] = nz;
                    if (uvs != null) {
                        inter[v * 8 + 6] = uvs[v * 2];
                        inter[v * 8 + 7] = uvs[v * 2 + 1];
                    }
                }

                Primitive p = new Primitive();
                p.vertexCount = vertCount;
                p.interleaved = ByteBuffer.allocateDirect(inter.length * 4)
                        .order(ByteOrder.nativeOrder()).asFloatBuffer();
                p.interleaved.put(inter).position(0);

                boolean useInt = false;
                for (int idx : indices) if (idx > 65535) { useInt = true; break; }
                p.indexCount = indices.length;
                if (useInt) {
                    p.indexType = 0x1405; // GL_UNSIGNED_INT
                    ByteBuffer ib = ByteBuffer.allocateDirect(indices.length * 4).order(ByteOrder.nativeOrder());
                    IntBuffer ibi = ib.asIntBuffer();
                    ibi.put(indices).position(0);
                    p.indices = ib;
                } else {
                    p.indexType = GLES20.GL_UNSIGNED_SHORT;
                    ByteBuffer ib = ByteBuffer.allocateDirect(indices.length * 2).order(ByteOrder.nativeOrder());
                    ShortBuffer sbi = ib.asShortBuffer();
                    for (int idx : indices) sbi.put((short) idx);
                    sbi.position(0);
                    p.indices = ib;
                }

                p.textureId = 0;
                if (prim.has("material") && materials != null) {
                    JSONObject mat = materials.getJSONObject(prim.getInt("material"));
                    if (mat.has("pbrMetallicRoughness")) {
                        JSONObject pbr = mat.getJSONObject("pbrMetallicRoughness");
                        if (pbr.has("baseColorFactor")) {
                            JSONArray c = pbr.getJSONArray("baseColorFactor");
                            p.baseColor[0] = (float) c.getDouble(0);
                            p.baseColor[1] = (float) c.getDouble(1);
                            p.baseColor[2] = (float) c.getDouble(2);
                            p.baseColor[3] = (float) c.getDouble(3);
                        }
                        if (pbr.has("baseColorTexture") && textures != null) {
                            int texIndex = pbr.getJSONObject("baseColorTexture").getInt("index");
                            JSONObject tex = textures.getJSONObject(texIndex);
                            int source = tex.getInt("source");
                            if (source >= 0 && source < imageBytes.size() && imageBytes.get(source) != null) {
                                p.textureId = -1 - source; // mark for later upload: -(source+1)
                            }
                        }
                    }
                }
                model.primitives.add(p);
            }
        }

        // Normalize: center + scale
        if (!allPos.isEmpty()) {
            float minX=1e9f,minY=1e9f,minZ=1e9f,maxX=-1e9f,maxY=-1e9f,maxZ=-1e9f;
            for (float[] p : allPos) {
                minX=Math.min(minX,p[0]); minY=Math.min(minY,p[1]); minZ=Math.min(minZ,p[2]);
                maxX=Math.max(maxX,p[0]); maxY=Math.max(maxY,p[1]); maxZ=Math.max(maxZ,p[2]);
            }
            float cx=(minX+maxX)*0.5f, cy=(minY+maxY)*0.5f, cz=(minZ+maxZ)*0.5f;
            float sx=maxX-minX, sy=maxY-minY, sz=maxZ-minZ;
            float maxExt = Math.max(sx, Math.max(sy, sz));
            float scale = maxExt > 1e-6f ? (targetMaxExtent / maxExt) : 1f;
            model.radius = maxExt * scale * 0.5f;

            for (Primitive p : model.primitives) {
                FloatBuffer fb = p.interleaved;
                for (int v = 0; v < p.vertexCount; v++) {
                    int o = v * 8;
                    float x = (fb.get(o) - cx) * scale;
                    float y = (fb.get(o + 1) - cy) * scale;
                    float z = (fb.get(o + 2) - cz) * scale;
                    fb.put(o, x); fb.put(o + 1, y); fb.put(o + 2, z);
                }
                fb.position(0);
            }

            // Upload textures on GL thread — store raw bytes temporarily via textureId marker
            model._imageBytes = imageBytes;
        }
        Log.i(TAG, "Loaded primitives=" + model.primitives.size() + " radius=" + model.radius);
        return model;
    }

    // temporary image storage until uploadTextures() is called on GL thread
    private List<byte[]> _imageBytes;

    /** Call on GL thread after load. */
    public void uploadTextures() {
        if (_imageBytes == null) return;
        int[] uploaded = new int[_imageBytes.size()];
        for (int i = 0; i < _imageBytes.size(); i++) {
            byte[] png = _imageBytes.get(i);
            if (png == null) { uploaded[i] = 0; continue; }
            Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bmp == null) { uploaded[i] = 0; continue; }
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            bmp.recycle();
            uploaded[i] = tex[0];
        }
        for (Primitive p : primitives) {
            if (p.textureId < 0) {
                int src = -p.textureId - 1;
                p.textureId = (src >= 0 && src < uploaded.length) ? uploaded[src] : 0;
            }
        }
        _imageBytes = null;
    }

    private static void computeWorld(int i, int[] parent, float[][] local, float[][] world,
                                     boolean[] computed, float[] tmp) {
        if (computed[i]) return;
        if (parent[i] < 0) {
            System.arraycopy(local[i], 0, world[i], 0, 16);
        } else {
            computeWorld(parent[i], parent, local, world, computed, tmp);
            Matrix.multiplyMM(world[i], 0, world[parent[i]], 0, local[i], 0);
        }
        computed[i] = true;
    }

    private static void quatToMatrix(float[] m, float x, float y, float z, float w) {
        float xx=x*x, yy=y*y, zz=z*z;
        float xy=x*y, xz=x*z, yz=y*z, wx=w*x, wy=w*y, wz=w*z;
        m[0]=1-2*(yy+zz); m[1]=2*(xy+wz); m[2]=2*(xz-wy); m[3]=0;
        m[4]=2*(xy-wz); m[5]=1-2*(xx+zz); m[6]=2*(yz+wx); m[7]=0;
        m[8]=2*(xz+wy); m[9]=2*(yz-wx); m[10]=1-2*(xx+yy); m[11]=0;
        m[12]=0; m[13]=0; m[14]=0; m[15]=1;
    }

    private static float[] readFloatVec(ByteBuffer bin, JSONArray accessors, JSONArray bufferViews,
                                        int accessorIndex, int comps) throws Exception {
        JSONObject acc = accessors.getJSONObject(accessorIndex);
        int count = acc.getInt("count");
        int bvIndex = acc.getInt("bufferView");
        JSONObject bv = bufferViews.getJSONObject(bvIndex);
        int offset = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0);
        int stride = bv.optInt("byteStride", comps * 4);
        float[] out = new float[count * comps];
        ByteBuffer view = bin.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            view.position(offset + i * stride);
            for (int c = 0; c < comps; c++) out[i * comps + c] = view.getFloat();
        }
        return out;
    }

    private static int[] readIndices(ByteBuffer bin, JSONArray accessors, JSONArray bufferViews,
                                     int accessorIndex) throws Exception {
        JSONObject acc = accessors.getJSONObject(accessorIndex);
        int count = acc.getInt("count");
        int componentType = acc.getInt("componentType");
        int bvIndex = acc.getInt("bufferView");
        JSONObject bv = bufferViews.getJSONObject(bvIndex);
        int offset = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0);
        ByteBuffer view = bin.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        view.position(offset);
        int[] out = new int[count];
        if (componentType == 5123) { // UNSIGNED_SHORT
            for (int i = 0; i < count; i++) out[i] = view.getShort() & 0xFFFF;
        } else if (componentType == 5125) { // UNSIGNED_INT
            for (int i = 0; i < count; i++) out[i] = view.getInt();
        } else if (componentType == 5121) { // UNSIGNED_BYTE
            for (int i = 0; i < count; i++) out[i] = view.get() & 0xFF;
        } else {
            throw new IllegalArgumentException("index type " + componentType);
        }
        return out;
    }

    private static int[] sequential(int n) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        return idx;
    }
}
