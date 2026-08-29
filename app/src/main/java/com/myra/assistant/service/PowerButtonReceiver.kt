package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PowerButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_SCREEN_OFF || action == Intent.ACTION_SCREEN_ON) {
            val now = System.currentTimeMillis()
            val diff = now - lastScreenToggleTime
            lastScreenToggleTime = now

            if (diff in 50..DOUBLE_PRESS_THRESHOLD_MS) {
                Log.d(TAG, "Double power button press detected ($diff ms). Showing overlay...")
                MyraOverlayService.start(context)
            }
        }
    }

    companion object {
        private const val TAG = "PowerButtonReceiver"
        private const val DOUBLE_PRESS_THRESHOLD_MS = 600L
        private var lastScreenToggleTime = 0L
    }
}
