package com.example.safe_kids.ui.child

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.safe_kids.data.RestrictionRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.example.safe_kids.auth.AuthConfig
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChildModeViewModel(
    private val context: Context,
    private val restrictionRepository: RestrictionRepository
) : ViewModel() {

    private val _allowedApps = MutableStateFlow<List<String>>(emptyList())
    val allowedApps: StateFlow<List<String>> = _allowedApps.asStateFlow()
    
    private val _firestoreAllowedApps = MutableStateFlow<List<String>>(emptyList())
    val firestoreAllowedApps: StateFlow<List<String>> = _firestoreAllowedApps.asStateFlow()
    
    private val _hasParentSetApps = MutableStateFlow(false)
    val hasParentSetApps: StateFlow<Boolean> = _hasParentSetApps.asStateFlow()

    init {
        observeRestrictions()
    }

    /**
     * FIX 1: Read allowed apps from Firestore children/{childId}/allowedApps
     * Only show apps where allowed = true
     * If parent has not set any allowed apps yet, show "No apps approved" message
     */
    fun observeFirestoreAllowedApps(childId: String) {
        viewModelScope.launch {
            FirebaseFirestore.getInstance()
                .collection(AuthConfig.APP_USAGE_LIMITS_COLLECTION)
                .document(childId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        _hasParentSetApps.value = false
                        _firestoreAllowedApps.value = emptyList()
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        val approvedPkgs = snapshot.get("alwaysAllowedApps") as? List<String> ?: emptyList()
                        val approved = approvedPkgs.mapNotNull { packageName ->
                            try {
                                val pm = context.packageManager
                                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                            } catch (e: Exception) {
                                packageName
                            }
                        }
                        _hasParentSetApps.value = approved.isNotEmpty()
                        _firestoreAllowedApps.value = approved
                    } else {
                        _hasParentSetApps.value = false
                        _firestoreAllowedApps.value = emptyList()
                    }
                }
        }
    }

    private fun observeRestrictions() {
        viewModelScope.launch {
            // Combine blocked and always allowed apps to calculate the final list
            combine(
                restrictionRepository.getBlockedAppsFlow(),
                restrictionRepository.getAlwaysAllowedAppsFlow()
            ) { blocked, alwaysAllowed ->
                val blockedPackages = blocked.map { it.packageName }.toSet()
                val alwaysAllowedPackages = alwaysAllowed.map { it.packageName }.toSet()
                
                calculateAllowedApps(blockedPackages, alwaysAllowedPackages)
            }.collect { list ->
                _allowedApps.value = list
            }
        }
    }

    private fun calculateAllowedApps(blockedPackages: Set<String>, alwaysAllowedPackages: Set<String>): List<String> {
        // If parent hasn't set anything, show nothing
        if (blockedPackages.isEmpty() && alwaysAllowedPackages.isEmpty()) return emptyList()

        val pm = context.packageManager
        val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
        mainIntent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)

        val resolvedInfos = pm.queryIntentActivities(mainIntent, 0)
        return resolvedInfos
            .filter { info ->
                val pkg = info.activityInfo.packageName
                (pkg !in blockedPackages || pkg in alwaysAllowedPackages) && pkg != context.packageName
            }
            .map { it.loadLabel(pm).toString() }
            .distinct()
            .sorted()
    }
}