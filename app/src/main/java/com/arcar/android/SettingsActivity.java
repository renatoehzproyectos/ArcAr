package com.arcar.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Simple rebind UI: tap an action, then press a controller button.
 */
public class SettingsActivity extends Activity {

    private InputMapper mapper;
    private Action waitingFor = null;
    private TextView status;
    private final java.util.EnumMap<Action, TextView> labels = new java.util.EnumMap<>(Action.class);

    private static final Action[] BINDABLE = {
            Action.JUMP, Action.BOOST, Action.POWERSLIDE,
            Action.AIR_ROLL_LEFT, Action.AIR_ROLL_RIGHT,
            Action.BALL_CAM, Action.RESET
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mapper = new InputMapper(this);
        status = findViewById(R.id.status);
        LinearLayout list = findViewById(R.id.bind_list);

        for (Action a : BINDABLE) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 12, 0, 12);

            TextView name = new TextView(this);
            name.setText(pretty(a));
            name.setTextSize(16);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView val = new TextView(this);
            val.setText(InputMapper.keyCodeLabel(mapper.getBinding(a)));
            val.setTextSize(16);
            labels.put(a, val);

            Button bind = new Button(this);
            bind.setText("Bind");
            final Action action = a;
            bind.setOnClickListener(v -> {
                waitingFor = action;
                status.setText("Press a button for " + pretty(action) + "…");
            });

            row.addView(name);
            row.addView(val);
            row.addView(bind);
            list.addView(row);
        }

        findViewById(R.id.btn_reset_defaults).setOnClickListener(v -> {
            mapper.resetBindings();
            refreshLabels();
            status.setText("Defaults restored.");
            Toast.makeText(this, "Defaults restored", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        status.setText("Tap Bind, then press a controller / keyboard key.\n"
                + "Axes: Left stick steer/pitch, Right stick yaw, LT/RT throttle.");
    }

    private void refreshLabels() {
        for (Action a : BINDABLE) {
            TextView tv = labels.get(a);
            if (tv != null) tv.setText(InputMapper.keyCodeLabel(mapper.getBinding(a)));
        }
    }

    private static String pretty(Action a) {
        switch (a) {
            case AIR_ROLL_LEFT: return "Air Roll Left";
            case AIR_ROLL_RIGHT: return "Air Roll Right";
            case POWERSLIDE: return "Powerslide";
            case BALL_CAM: return "Ball Cam Toggle";
            default: return a.name().charAt(0) + a.name().substring(1).toLowerCase().replace('_', ' ');
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (waitingFor != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                waitingFor = null;
                status.setText("Bind cancelled.");
                return true;
            }
            mapper.setBinding(waitingFor, keyCode);
            labels.get(waitingFor).setText(InputMapper.keyCodeLabel(keyCode));
            status.setText(pretty(waitingFor) + " → " + InputMapper.keyCodeLabel(keyCode));
            waitingFor = null;
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
