package com.arcar.android;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Bind digital sim actions. One key may be shared across multiple actions
 * (e.g. L2 → Decelerate + Air Roll Left).
 * Steer/pitch/yaw = analogue sticks only.
 */
public class SettingsActivity extends Activity {

    private InputMapper mapper;
    private Action waitingAction = null;
    private TextView status;
    private final java.util.EnumMap<Action, TextView> labels = new java.util.EnumMap<>(Action.class);

    private static final Action[] BINDABLE = {
            Action.ACCELERATE,
            Action.DECELERATE,
            Action.JUMP,
            Action.BOOST,
            Action.POWERSLIDE,
            Action.AIR_ROLL_LEFT,
            Action.AIR_ROLL_RIGHT
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mapper = new InputMapper(this);
        status = findViewById(R.id.status);
        LinearLayout list = findViewById(R.id.bind_list);

        // Car selector
        TextView carTitle = new TextView(this);
        carTitle.setText("CAR BODY");
        carTitle.setTextColor(0xFFFFCC66);
        carTitle.setTextSize(14);
        list.addView(carTitle);
        LinearLayout carRow = new LinearLayout(this);
        carRow.setOrientation(LinearLayout.HORIZONTAL);
        final String selected = CarCatalog.getSelectedId(this);
        for (final CarCatalog.Entry e : CarCatalog.CARS) {
            Button b = new Button(this);
            b.setText(e.displayName);
            if (e.id.equals(selected)) b.setTextColor(0xFFFFCC66);
            b.setOnClickListener(v -> {
                CarCatalog.setSelectedId(this, e.id);
                status.setText("Car: " + e.displayName + " (applies next launch)");
                // update button colors
                for (int i = 0; i < carRow.getChildCount(); i++) {
                    Button bb = (Button) carRow.getChildAt(i);
                    bb.setTextColor(0xFFFFFFFF);
                }
                b.setTextColor(0xFFFFCC66);
            });
            carRow.addView(b);
        }
        list.addView(carRow);
        TextView carNote = new TextView(this);
        carNote.setText("Models: CC-BY (Sketchfab). Restart match after switching.\n");
        carNote.setTextColor(0xFF888888);
        carNote.setTextSize(12);
        list.addView(carNote);

        // Infinite boost toggle at top
        CheckBox infBoost = new CheckBox(this);
        infBoost.setText("Infinite boost");
        infBoost.setTextColor(0xFFFFFFFF);
        infBoost.setChecked(mapper.isInfiniteBoost());
        infBoost.setOnCheckedChangeListener((b, checked) -> {
            mapper.setInfiniteBoost(checked);
            status.setText(checked ? "Infinite boost ON" : "Infinite boost OFF");
        });
        list.addView(infBoost);

        TextView hint = new TextView(this);
        hint.setText("\nSteer / pitch / yaw = left & right sticks only (no key binds).\n"
                + "One key can be on several actions at once (e.g. L2 = brake + air roll left).\n");
        hint.setTextColor(0xFFAAAAAA);
        hint.setTextSize(13);
        list.addView(hint);

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

            Button add = new Button(this);
            add.setText("Add key");
            add.setOnClickListener(v -> {
                waitingAction = a;
                status.setText("Press a key to ADD to " + pretty(a) + "… (Back=cancel)");
            });

            Button clear = new Button(this);
            clear.setText("Clear");
            clear.setOnClickListener(v -> {
                mapper.clearBindings(a);
                labels.get(a).setText(mapper.formatBindings(a));
                status.setText(pretty(a) + " cleared");
            });

            btns.addView(add);
            btns.addView(clear);

            row.addView(name);
            row.addView(val);
            row.addView(btns);
            list.addView(row);
        }

        findViewById(R.id.btn_reset_defaults).setOnClickListener(v -> {
            mapper.resetBindings();
            infBoost.setChecked(false);
            refreshLabels();
            status.setText("Defaults restored.");
            Toast.makeText(this, "Defaults restored", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        status.setText("Add key = append (does not remove from other actions).\n"
                + "Same button on two actions = both fire together.");
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
                status.setText("Cancelled.");
                return true;
            }
            mapper.addBinding(waitingAction, keyCode);
            labels.get(waitingAction).setText(mapper.formatBindings(waitingAction));
            status.setText(pretty(waitingAction) + " += " + InputMapper.keyCodeLabel(keyCode)
                    + "  (still on other actions if you added it there too)");
            waitingAction = null;
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
