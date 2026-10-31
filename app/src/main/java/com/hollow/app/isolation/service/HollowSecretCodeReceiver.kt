package com.hollow.app.isolation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hollow.app.isolation.hook.SystemStealthHook

/**
 * Allows opening Hollow when completely hidden from all launchers by dialing
 * `*#*#465569#*#*` ("HOLLOW" on the phone keypad), matching Vector's secret dialer
 * code architecture (`VectorService.kt`).
 */
class HollowSecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == "android.provider.Telephony.SECRET_CODE" ||
            action == "android.telephony.action.SECRET_CODE"
        ) {
            SystemStealthHook.launchHollowFromSystemUi(context)
        }
    }
}
