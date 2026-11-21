package com.example.myapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the Stop button of the "MyApp is running" notification. */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutomationService.instance?.stopFromNotification()
    }
}
