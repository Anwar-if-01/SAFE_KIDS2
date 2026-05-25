package com.example.safe_kids.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.safe_kids.MainActivity

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        
        Log.d("SecretCodeReceiver", "Secret code dialed: ${intent.data}")
        
        // Launch the application
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(launchIntent)
    }
}
