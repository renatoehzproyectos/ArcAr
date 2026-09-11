package com.arcar.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Mode picker: Baseplate / Import BakkesMod map / Champions Field (locked).
 */
public class ModeSelectActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_MAP_ID = "map_id";
    public static final String EXTRA_MAP_NAME = "map_name";
    public static final String MODE_BASEPLATE = "baseplate";
    public static final String MODE_IMPORTED = "imported";

    private static final int REQ_PICK_ZIP = 1001;

    private TextView importStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_mode_select);

        importStatus = findViewById(R.id.import_status);
        refreshImportStatus();

        findViewById(R.id.btn_mode_back).setOnClickListener(v -> finish());

        findViewById(R.id.card_baseplate).setOnClickListener(v -> launchGame(MODE_BASEPLATE, null, null));

        findViewById(R.id.card_import).setOnClickListener(v -> onImportCard());

        findViewById(R.id.card_champions).setOnClickListener(v ->
                Toast.makeText(this, "Champions Field — próximamente", Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshImportStatus();
    }

    private void refreshImportStatus() {
        List<MapCatalog.Entry> maps = MapCatalog.list(this);
        MapCatalog.Entry sel = MapCatalog.getSelected(this);
        if (maps.isEmpty()) {
            importStatus.setText("Ningún mapa importado");
            importStatus.setTextColor(0xFF6688AA);
        } else if (sel != null) {
            importStatus.setText("Seleccionado: " + sel.name);
            importStatus.setTextColor(0xFF40B8FF);
        } else {
            importStatus.setText(maps.size() + " mapa(s) · tocá para elegir");
            importStatus.setTextColor(0xFF99BBDD);
        }
    }

    private void onImportCard() {
        List<MapCatalog.Entry> maps = MapCatalog.list(this);
        if (maps.isEmpty()) {
            openZipPicker();
            return;
        }
        String[] items = new String[maps.size() + 1];
        for (int i = 0; i < maps.size(); i++) {
            MapCatalog.Entry e = maps.get(i);
            boolean sel = e.id.equals(MapCatalog.getSelectedId(this));
            items[i] = (sel ? "● " : "○ ") + e.name;
        }
        items[maps.size()] = "＋ Importar otro .zip…";

        new AlertDialog.Builder(this)
                .setTitle("Mapas importados")
                .setItems(items, (d, which) -> {
                    if (which == maps.size()) {
                        openZipPicker();
                    } else {
                        MapCatalog.Entry e = maps.get(which);
                        MapCatalog.setSelectedId(this, e.id);
                        refreshImportStatus();
                        launchGame(MODE_IMPORTED, e.id, e.name);
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void openZipPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        // also allow generic
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream"
        });
        try {
            startActivityForResult(intent, REQ_PICK_ZIP);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ZIP || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            // Persist read permission if possible
            final int takeFlags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (Exception ignored) {}

            MapCatalog.Entry e = MapCatalog.importZip(this, uri);
            refreshImportStatus();
            Toast.makeText(this, "Importado: " + e.name, Toast.LENGTH_SHORT).show();
            new AlertDialog.Builder(this)
                    .setTitle(e.name)
                    .setMessage("Mapa BakkesMod importado.\n\n"
                            + "Nota Alpha 0.1: la geometría .udk aún no se aplica a la física; "
                            + "jugarás sobre baseplate con este mapa seleccionado para el futuro pipeline.")
                    .setPositiveButton("JUGAR", (d, w) ->
                            launchGame(MODE_IMPORTED, e.id, e.name))
                    .setNegativeButton("OK", null)
                    .show();
        } catch (Exception ex) {
            Toast.makeText(this, "Error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void launchGame(String mode, String mapId, String mapName) {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(EXTRA_MODE, mode);
        if (mapId != null) i.putExtra(EXTRA_MAP_ID, mapId);
        if (mapName != null) i.putExtra(EXTRA_MAP_NAME, mapName);
        startActivity(i);
    }
}
