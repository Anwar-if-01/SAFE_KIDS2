package com.example.safe_kids.services

import android.util.Log
import com.example.safe_kids.data.UsageRepository
import com.example.safe_kids.auth.AuthConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppLimit(val dailyLimitMs: Long = 0L, val weeklyLimitMs: Long = 0L)

class ScreenTimeLimitChecker(private val context: android.content.Context) {
    private val TAG = "ScreenTimeLimitChecker"
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val usageRepository = UsageRepository(context)
    private var limits: Map<String, AppLimit> = emptyMap()
    private var listener: com.google.firebase.firestore.ListenerRegistration? = null

    fun startListening() {
        val user = auth.currentUser ?: return
        listener?.remove()
        listener = firestore.collection("children").document(user.uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e(TAG, "Limits listener failed", e)
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val map = snapshot.get("appLimits") as? Map<String, Any>
                if (map != null) {
                    val prepared = mutableMapOf<String, AppLimit>()
                    for ((pkg, value) in map) {
                        val v = value as? Map<String, Any> ?: continue
                        val daily = (v["dailyLimitMs"] as? Number)?.toLong() ?: 0L
                        val weekly = (v["weeklyLimitMs"] as? Number)?.toLong() ?: 0L
                        prepared[pkg] = AppLimit(dailyLimitMs = daily, weeklyLimitMs = weekly)
                    }
                    limits = prepared
                    Log.d(TAG, "Loaded ${limits.size} app limits from Firestore")
                }
            }
    }

    fun stopListening() {
        listener?.remove()
        listener = null
    }

    suspend fun checkAndEnforce(packageName: String) {
        try {
            val user = auth.currentUser ?: return
            val limit = limits[packageName] ?: return

            val todayUsage = withContext(Dispatchers.IO) {
                val date = usageRepository.getCurrentDate()
                val entry = usageRepository.getUsageForTodaySync().find { it.packageName == packageName }
                entry?.durationMillis ?: 0L
            }

            val weeklyUsage = withContext(Dispatchers.IO) {
                usageRepository.getWeeklyUsageForPackage(packageName)
            }

            if ((limit.dailyLimitMs > 0 && todayUsage >= limit.dailyLimitMs) ||
                (limit.weeklyLimitMs > 0 && weeklyUsage >= limit.weeklyLimitMs)) {

                Log.w(TAG, "Limit exceeded for $packageName: today=$todayUsage weekly=$weeklyUsage")

                // Trigger block in AccessibilityService if available
                val svc = SafeKidsAccessibilityService.getInstance()
                if (svc != null) {
                    withContext(Dispatchers.Main) {
                        svc.blockCurrentApp(packageName)
                    }
                }

                // Ensure a usageStats document exists/updated so parent can see immediate block
                val statsRef = firestore.collection("children").document(user.uid)
                    .collection("usageStats").document(packageName)
                statsRef.set(mapOf(
                    "dailyUsageMs" to todayUsage,
                    "weeklyUsageMs" to weeklyUsage,
                    "lastUpdated" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                ), com.google.firebase.firestore.SetOptions.merge())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check/enforce screen time limit", e)
        }
    }
}
