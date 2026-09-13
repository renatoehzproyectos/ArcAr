package com.arcar.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Import / store BakkesMod-style map packages (.zip containing .udk or .upk).
 * Geometry from UDK is not applied to physics yet (Alpha); the package is kept
 * for future mesh extraction. Play still uses the baseplate arena.
 */
public final class MapCatalog {
    private static final String TAG = "MapCatalog";
    private static final String PREF = "arcar_maps_v1";
    private static final String KEY_SELECTED = "selected_id";

    public static final class Entry {
        public final String id;       // folder name
        public final String name;     // display (file base name)
        public final String packageFile; // relative path under maps dir
        public Entry(String id, String name, String packageFile) {
            this.id = id;
            this.name = name;
            this.packageFile = packageFile;
        }
    }

    private MapCatalog() {}

    public static File mapsDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "maps");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static List<Entry> list(Context ctx) {
        File root = mapsDir(ctx);
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) return Collections.emptyList();
        List<Entry> out = new ArrayList<>();
        for (File dir : dirs) {
            // Prefer an extracted .glb (real geometry) over the raw .udk/.upk.
            File[] glbs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".glb"));
            File[] pkgs = dir.listFiles((d, n) -> {
                String lower = n.toLowerCase();
                return lower.endsWith(".udk") || lower.endsWith(".upk");
            });
            File chosen = (glbs != null && glbs.length > 0) ? glbs[0]
                    : (pkgs != null && pkgs.length > 0) ? pkgs[0] : null;
            if (chosen != null) {
                String name = chosen.getName().replaceAll("\\.(glb|udk|upk)$", "");
                out.add(new Entry(dir.getName(), name, chosen.getName()));
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public static String getSelectedId(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_SELECTED, null);
    }

    public static void setSelectedId(Context ctx, String id) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_SELECTED, id).apply();
    }

    public static Entry getSelected(Context ctx) {
        String id = getSelectedId(ctx);
        if (id == null) return null;
        for (Entry e : list(ctx)) if (e.id.equals(id)) return e;
        return null;
    }

    /**
     * Import a map zip from a content Uri (SAF).
     * Accepts either a real, extracted .glb (from rl-mobile-extractor — this
     * is what actually gets rendered) or a raw BakkesMod .udk/.upk (kept for
     * future extraction, not currently rendered).
     * @return imported Entry or null on failure
     */
    public static Entry importZip(Context ctx, Uri uri) throws Exception {
        String display = uri.getLastPathSegment();
        if (display == null) display = "map";
        // strip path prefixes from document IDs
        int slash = Math.max(display.lastIndexOf('/'), display.lastIndexOf(':'));
        if (slash >= 0) display = display.substring(slash + 1);
        if (display.toLowerCase().endsWith(".zip")) {
            display = display.substring(0, display.length() - 4);
        }
        String safeId = display.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safeId.isEmpty()) safeId = "map_" + System.currentTimeMillis();

        File destDir = new File(mapsDir(ctx), safeId);
        if (destDir.exists()) {
            // clear previous
            File[] old = destDir.listFiles();
            if (old != null) for (File f : old) f.delete();
        } else {
            destDir.mkdirs();
        }

        String foundPkg = null;
        String foundGlb = null;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(in)) {
            if (in == null) throw new IllegalStateException("No se pudo abrir el archivo");
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                int li = name.lastIndexOf('/');
                if (li >= 0) name = name.substring(li + 1);
                String lower = name.toLowerCase();
                boolean isGlb = lower.endsWith(".glb");
                boolean isPkg = lower.endsWith(".udk") || lower.endsWith(".upk");
                if (!isGlb && !isPkg) {
                    zis.closeEntry();
                    continue;
                }
                // Skip if we already have this kind (keep first of each kind).
                if ((isGlb && foundGlb != null) || (isPkg && foundPkg != null)) {
                    zis.closeEntry();
                    continue;
                }
                File out = new File(destDir, name);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                }
                if (isGlb) foundGlb = name; else foundPkg = name;
                zis.closeEntry();
                if (foundGlb != null) break; // .glb is all we need to render
            }
        }

        String chosen = foundGlb != null ? foundGlb : foundPkg;
        if (chosen == null) {
            // cleanup empty dir
            destDir.delete();
            throw new IllegalArgumentException(
                    "El ZIP no contiene un mapa (.glb extraído o .udk / .upk)");
        }

        Log.i(TAG, "Imported map " + safeId + " file=" + chosen);
        Entry e = new Entry(safeId, chosen.replaceAll("\\.(glb|udk|upk)$", ""), chosen);
        setSelectedId(ctx, e.id);
        return e;
    }
}
