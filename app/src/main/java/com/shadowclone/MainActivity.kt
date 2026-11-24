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
            text = "Rec: open the app you want, press Rec, then use it normally. Every touch is " +
                "saved as a step and passed through to the app.\n\n" +
                "No-Tap: touch a screen that must never be tapped (purchases, premium currency). " +
                "That touch is NOT passed through, and a run stops the moment the screen appears.\n\n" +
                "Play: replays the steps, waiting for each screen instead of using fixed delays. " +
                "Stop ends a run or a recording. Clear wipes the macro. Move and Hide get the panel " +
                "out of the way."
        }
        val btn = Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        // Hide on the panel removes it; this is the only way back without toggling the service.
        val overlayBtn = Button(this).apply {
            text = "Show / hide overlay"
            setOnClickListener {
                val svc = ShadowService.instance
                status.text = when {
                    svc == null -> "Service: off"
                    svc.toggleOverlay() -> "Overlay: shown"
                    else -> "Overlay: hidden"
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(status)
            addView(btn)
            addView(overlayBtn)
            addView(hint)
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (ShadowService.instance != null) "Service: running" else "Service: off"
    }
}
