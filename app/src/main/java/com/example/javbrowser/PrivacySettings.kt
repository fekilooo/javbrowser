package com.example.javbrowser

import android.content.Context
import android.content.SharedPreferences

class PrivacySettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("privacy_prefs", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_SELECTED_ICON = "selected_icon"
        private const val KEY_LAST_UNLOCK_TIME = "last_unlock_time"
        
        const val ICON_DEFAULT = "default"
        const val ICON_CALCULATOR = "calculator"
        const val ICON_NOTES = "notes"
        const val ICON_FILE = "file"
    }
    
    var isLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_LOCK_ENABLED, value).apply()
    
    var selectedIcon: String
        get() = prefs.getString(KEY_SELECTED_ICON, ICON_DEFAULT) ?: ICON_DEFAULT
        set(value) = prefs.edit().putString(KEY_SELECTED_ICON, value).apply()
    
    var lastUnlockTime: Long
        get() = prefs.getLong(KEY_LAST_UNLOCK_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UNLOCK_TIME, value).apply()
    
    fun shouldLock(): Boolean {
        if (!isLockEnabled) return false
        
        val currentTime = System.currentTimeMillis()
        val oneHourMillis = 60 * 60 * 1000L // 1 hour in milliseconds
        
        return (currentTime - lastUnlockTime) > oneHourMillis
    }
    
    fun updateUnlockTime() {
        lastUnlockTime = System.currentTimeMillis()
    }

    // PIN Code Support
    var pinCode: String?
        get() = prefs.getString("app_pin_code", null)
        set(value) = prefs.edit().putString("app_pin_code", value).apply()

    fun isPinSet(): Boolean {
        return !pinCode.isNullOrEmpty()
    }

    fun validatePin(inputPin: String): Boolean {
        return inputPin == pinCode
    }
}
