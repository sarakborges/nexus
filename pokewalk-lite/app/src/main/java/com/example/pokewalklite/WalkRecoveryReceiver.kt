package com.example.pokewalklite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class WalkRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!WalkState.isRunning(context)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, WalkService::class.java))
        } catch (_: Throwable) {
            // The run state remains persisted and will reconcile when the app/service can start again.
        }
    }
}
