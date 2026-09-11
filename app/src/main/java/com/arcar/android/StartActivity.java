package com.arcar.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

/** Clean boot screen: title + PLAY + CONFIG. */
public class StartActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_start);

        findViewById(R.id.btn_play).setOnClickListener(v ->
                startActivity(new Intent(this, ModeSelectActivity.class)));

        findViewById(R.id.btn_start_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }
}
