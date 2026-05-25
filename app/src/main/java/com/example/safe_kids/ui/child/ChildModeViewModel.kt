package com.example.safe_kids.ui.child

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.safe_kids.data.RestrictionRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChildModeViewModel(
    private val context: Context,
    private val restrictionRepository: RestrictionRepository
) : ViewModel() {

    private val _allowedApps = MutableStateFlow<List<String>>(emptyList())
    val allowedApps: StateFlow<List<String>> = _allowedApps.asStateFlow()

    init {
        observeRestrictions()
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
        val pm = context.packageManager
        val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
        mainIntent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        
        val resolvedInfos = pm.queryIntentActivities(mainIntent, 0)
        return resolvedInfos
            .filter { info ->
                val pkg = info.activityInfo.packageName
                // Logic: (Not Blocked) OR (Always Allowed)
                // Filter out the app itself and system apps if needed, but here we focus on the restrictions
                (pkg !in blockedPackages || pkg in alwaysAllowedPackages) && pkg != context.packageName
            }
            .map { it.loadLabel(pm).toString() }
            .distinct()
            .sorted()
    }
}
