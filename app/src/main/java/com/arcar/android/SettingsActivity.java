package com.arcar.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Rebind UI for simulation controls only.
 * Each action has two slots (primary + secondary) so two keys can map to one action.
 */
public class SettingsActivity extends Activity {

    private InputMapper mapper;
    private Action waitingAction = null;
    private int waitingSlot = 0;
    private TextView status;
    private final java.util.EnumMap<Action, TextView> labels = new java.util.EnumMap<>(Action.class);

    /** Only simulation actions — no cam / reset / console. */
    private static final Action[] BINDABLE = {
            Action.ACCELERATE,
            Action.DECELERATE,
            Action.STEER_LEFT,
            Action.STEER_RIGHT,
            Action.PITCH_UP,
            Action.PITCH_DOWN,
            Action.YAW_LEFT,
            Action.YAW_RIGHT,
            Action.AIR_ROLL_LEFT,
            Action.AIR_ROLL_RIGHT,
            Action.JUMP,
            Action.BOOST,
            Action.POWERSLIDE
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
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, 10, 0, 10);

            TextView name = new TextView(this);
            name.setText(pretty(a));
            name.setTextSize(16);
            name.setTextColor(0xFFFFFFFF);

            TextView val = new TextView(this);
            val.setText(mapper.formatBindings(a));
            val.setTextSize(14);
            val.setTextColor(0xFFAAAAAA);
            labels.put(a, val);

            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);

            Button b1 = new Button(this);
            b1.setText("Bind 1");
            b1.setOnClickListener(v -> startWait(a, InputMapper.SLOT_PRIMARY));

            Button b2 = new Button(this);
            b2.setText("Bind 2");
            b2.setOnClickListener(v -> startWait(a, InputMapper.SLOT_SECONDARY));

            Button clear = new Button(this);
            clear.setText("Clear");
            clear.setOnClickListener(v -> {
                mapper.clearBinding(a, InputMapper.SLOT_PRIMARY);
                mapper.clearBinding(a, InputMapper.SLOT_SECONDARY);
                labels.get(a).setText(mapper.formatBindings(a));
                status.setText(pretty(a) + " cleared");
            });

            btns.addView(b1);
            btns.addView(b2);
            btns.addView(clear);

            row.addView(name);
            row.addView(val);
            row.addView(btns);
            list.addView(row);
        }

        findViewById(R.id.btn_reset_defaults).setOnClickListener(v -> {
            mapper.resetBindings();
            refreshLabels();
            status.setText("Defaults restored.");
            Toast.makeText(this, "Defaults restored", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        status.setText("Simulation binds only (no cam / reset / console).\n"
                + "Use Bind 1 and Bind 2 for two keys per action.\n"
                + "Sticks/triggers still work: LT/RT throttle, left stick steer/pitch.");
    }

    private void startWait(Action a, int slot) {
        waitingAction = a;
        waitingSlot = slot;
        status.setText("Press a key for " + pretty(a)
                + " (slot " + (slot + 1) + ")…  Back=cancel");
    }

    private void refreshLabels() {
        for (Action a : BINDABLE) {
            TextView tv = labels.get(a);
            if (tv != null) tv.setText(mapper.formatBindings(a));
        }
    }

    private static String pretty(Action a) {
        switch (a) {
            case ACCELERATE: return "Accelerate";
            case DECELERATE: return "Decelerate / Reverse";
            case STEER_LEFT: return "Steer Left";
            case STEER_RIGHT: return "Steer Right";
            case PITCH_UP: return "Pitch Up";
            case PITCH_DOWN: return "Pitch Down";
            case YAW_LEFT: return "Yaw Left";
            case YAW_RIGHT: return "Yaw Right";
            case AIR_ROLL_LEFT: return "Air Roll Left";
            case AIR_ROLL_RIGHT: return "Air Roll Right";
            case POWERSLIDE: return "Powerslide";
            case JUMP: return "Jump";
            case BOOST: return "Boost";
            default: return a.name();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (waitingAction != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                waitingAction = null;
                status.setText("Bind cancelled.");
                return true;
            }
            // Ignore pure modifiers as sole bind? allow them
            mapper.setBinding(waitingAction, waitingSlot, keyCode);
            labels.get(waitingAction).setText(mapper.formatBindings(waitingAction));
            status.setText(pretty(waitingAction) + " slot " + (waitingSlot + 1)
                    + " → " + InputMapper.keyCodeLabel(keyCode));
            waitingAction = null;
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
