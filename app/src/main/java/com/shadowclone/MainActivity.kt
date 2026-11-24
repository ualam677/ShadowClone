package com.shadowclone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 16f }
        val hint = TextView(this).apply {
            textSize = 14f
            text = "Milestone 1: Crop a box on screen, Watch for it live, Tap where it matched.\n\n" +
                "Test runs the matcher self-check on the last frame. Frame saves that frame so it " +
                "can be inspected. Move flips the panel between bottom and top."
        }
        val btn = Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(btn)
            addView(hint)
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (ShadowService.instance != null) "Service: running" else "Service: off"
    }
}
