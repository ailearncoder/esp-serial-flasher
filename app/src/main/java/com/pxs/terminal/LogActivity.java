package com.pxs.terminal;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.TextView;

public class LogActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);
        String stack = getIntent().getStringExtra("stack");
        if (stack != null) {
            ((TextView) findViewById(R.id.text_log)).setText(stack);
        }
    }
}