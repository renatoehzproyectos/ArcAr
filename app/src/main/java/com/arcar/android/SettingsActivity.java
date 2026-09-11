package com.arcar.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Blue-themed keybind settings (CONTROLES → Asignación de teclas).
 */
public class SettingsActivity extends Activity {

    private InputMapper input;
    private LinearLayout bindList;
    private TextView hintListen;
    private Action listeningFor = null;
    private TextView listeningChip = null;

    private static final String[] ICONS = {
            "↑", "🚀", "▶", "◀", "〰", "◎", "↻", "↺"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_settings);

        input = new InputMapper(this);
        bindList = findViewById(R.id.bind_list);
        hintListen = findViewById(R.id.hint_listen);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_reset).setOnClickListener(v -> {
            input.resetBindings();
            listeningFor = null;
            listeningChip = null;
            rebuildRows();
            Toast.makeText(this, "Controles restablecidos", Toast.LENGTH_SHORT).show();
        });

        // Tabs are visual for now; CONTROLES is active
        findViewById(R.id.tab_juego).setOnClickListener(v ->
                Toast.makeText(this, "Próximamente", Toast.LENGTH_SHORT).show());
        findViewById(R.id.tab_camara).setOnClickListener(v ->
                Toast.makeText(this, "Próximamente", Toast.LENGTH_SHORT).show());
        findViewById(R.id.tab_interfaz).setOnClickListener(v ->
                Toast.makeText(this, "Próximamente", Toast.LENGTH_SHORT).show());

        rebuildRows();
    }

    private void rebuildRows() {
        bindList.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        Action[] actions = Action.values();
        for (int i = 0; i < actions.length; i++) {
            Action a = actions[i];
            View row = inf.inflate(R.layout.item_keybind_row, bindList, false);
            TextView icon = row.findViewById(R.id.row_icon);
            TextView label = row.findViewById(R.id.row_label);
            TextView bind = row.findViewById(R.id.row_bind);
            TextView more = row.findViewById(R.id.row_more);

            icon.setText(ICONS[i % ICONS.length]);
            label.setText(a.label);
            refreshChip(bind, a);

            bind.setOnClickListener(v -> startListening(a, bind));
            more.setOnClickListener(v -> showMoreMenu(a, bind));

            bindList.addView(row);
        }
    }

    private void refreshChip(TextView chip, Action a) {
        chip.setText(input.formatBindings(a));
        if (listeningFor == a) {
            chip.setBackgroundResource(R.drawable.bg_bind_chip_listening);
            chip.setText("…");
        } else {
            chip.setBackgroundResource(R.drawable.bg_bind_chip);
        }
    }

    private void startListening(Action a, TextView chip) {
        listeningFor = a;
        listeningChip = chip;
        hintListen.setText("Escuchando «" + a.label + "» — pulsa una tecla o botón…");
        rebuildRows();
    }

    private void cancelListening() {
        listeningFor = null;
        listeningChip = null;
        hintListen.setText("Toca un binding y pulsa una tecla o botón del mando.");
        rebuildRows();
    }

    private void showMoreMenu(Action a, TextView chip) {
        String[] items = {"Reasignar", "Quitar último binding", "Limpiar todos"};
        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        startListening(a, chip);
                    } else if (which == 1) {
                        int[] codes = input.getKeycodes(a);
                        if (codes.length > 0) input.removeBinding(a, codes[codes.length - 1]);
                        rebuildRows();
                    } else if (which == 2) {
                        input.clearBindings(a);
                        rebuildRows();
                    }
                })
                .show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (listeningFor != null) {
                cancelListening();
                return true;
            }
            finish();
            return true;
        }
        if (listeningFor != null && event.getRepeatCount() == 0) {
            // Ignore pure meta keys
            if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return true;
            input.addBinding(listeningFor, keyCode);
            Toast.makeText(this,
                    listeningFor.label + " → " + InputMapper.keyCodeLabel(keyCode),
                    Toast.LENGTH_SHORT).show();
            cancelListening();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Capture gamepad buttons while listening (before they are consumed elsewhere)
        if (listeningFor != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            int keyCode = event.getKeyCode();
            if (keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                input.addBinding(listeningFor, keyCode);
                Toast.makeText(this,
                        listeningFor.label + " → " + InputMapper.keyCodeLabel(keyCode),
                        Toast.LENGTH_SHORT).show();
                cancelListening();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
