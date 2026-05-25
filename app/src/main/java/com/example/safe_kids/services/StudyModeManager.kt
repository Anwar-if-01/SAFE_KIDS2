package com.example.safe_kids.services

import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory Study Mode manager. Updated by ForegroundProtectionService when Firestore changes.
 */
object StudyModeManager {
    private val active = AtomicBoolean(false)
    private var whitelist = mutableSetOf<String>()
    private var blocklist = mutableSetOf<String>()

    fun update(isActive: Boolean, wl: Set<String>, bl: Set<String>) {
        active.set(isActive)
        synchronized(this) {
            whitelist = wl.toMutableSet()
            blocklist = bl.toMutableSet()
        }
    }

    fun isActive(): Boolean = active.get()
    fun getWhitelist(): Set<String> = synchronized(this) { whitelist.toSet() }
    fun getBlocklist(): Set<String> = synchronized(this) { blocklist.toSet() }
}
