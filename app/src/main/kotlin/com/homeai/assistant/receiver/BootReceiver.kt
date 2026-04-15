package com.homeai.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.homeai.assistant.service.HomeAIService

/**
 * BootReceiver – auto-starts [HomeAIService] when the device boots.
 *
 * Listens for:
 *  - android.intent.action.BOOT_COMPLETED
 *  - android.intent.action.QUICKBOOT_POWERON (Samsung/HTC fast-boot)
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Boot action received: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val serviceIntent = Intent(context, HomeAIService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "HomeAIService started from boot")
        }
    }
}
