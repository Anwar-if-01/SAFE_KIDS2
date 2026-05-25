package com.example.safe_kids.data

import android.content.Context
import com.example.safe_kids.auth.AuthConfig
import com.example.safe_kids.data.local.AppDatabase
import com.example.safe_kids.data.local.AppRestrictionEntity
import com.example.safe_kids.data.local.RestrictionType
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers

class RestrictionRepository(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val restrictionDao = database.appRestrictionDao()
    private val firestore = FirebaseFirestore.getInstance()

    /**
     * Listens to Firestore for restriction changes and updates Room.
     * This ensures the local database is always synced with the parent's choices.
     */
    fun startRestrictionSync(childId: String): Flow<Unit> = callbackFlow {
        val registration = firestore.collection("app_usage_limits")
            .document(childId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    android.util.Log.e("RestrictionRepo", "Listen failed", e)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val blockedApps = snapshot.get("blockedApps") as? List<String> ?: emptyList()
                    val alwaysAllowedApps = snapshot.get("alwaysAllowedApps") as? List<String> ?: emptyList()
                    
                    val entities = mutableListOf<AppRestrictionEntity>()
                    blockedApps.forEach { pkg ->
                        entities.add(AppRestrictionEntity(pkg, "", RestrictionType.BLOCKED))
                    }
                    alwaysAllowedApps.forEach { pkg ->
                        entities.add(AppRestrictionEntity(pkg, "", RestrictionType.ALWAYS_ALLOWED))
                    }

                    // Perform atomic update in Room
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        restrictionDao.replaceAll(entities)
                        trySend(Unit)
                    }
                }
            }
        awaitClose { registration.remove() }
    }

    fun getBlockedAppsFlow(): Flow<List<AppRestrictionEntity>> {
        return restrictionDao.getRestrictionsByTypeFlow(RestrictionType.BLOCKED)
    }

    fun getAlwaysAllowedAppsFlow(): Flow<List<AppRestrictionEntity>> {
        return restrictionDao.getRestrictionsByTypeFlow(RestrictionType.ALWAYS_ALLOWED)
    }

    suspend fun isAppBlocked(packageName: String): Boolean {
        val restrictions = restrictionDao.getAllRestrictionsFlow().first()
        val isBlocked = restrictions.any { it.packageName == packageName && it.type == RestrictionType.BLOCKED }
        val isAlwaysAllowed = restrictions.any { it.packageName == packageName && it.type == RestrictionType.ALWAYS_ALLOWED }
        return isBlocked && !isAlwaysAllowed
    }
}
