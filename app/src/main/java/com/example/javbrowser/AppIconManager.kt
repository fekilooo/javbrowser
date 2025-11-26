package com.example.javbrowser

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

class AppIconManager(private val context: Context) {
    
    companion object {
        private const val MAIN_ACTIVITY = "com.example.javbrowser.MainActivity"
        private const val ALIAS_CALCULATOR = "com.example.javbrowser.MainActivityCalculator"
        private const val ALIAS_NOTES = "com.example.javbrowser.MainActivityNotes"
        private const val ALIAS_FILE = "com.example.javbrowser.MainActivityFile"
    }
    
    fun switchIcon(iconType: String) {
        val packageManager = context.packageManager
        
        // Disable all aliases first
        disableComponent(packageManager, MAIN_ACTIVITY)
        disableComponent(packageManager, ALIAS_CALCULATOR)
        disableComponent(packageManager, ALIAS_NOTES)
        disableComponent(packageManager, ALIAS_FILE)
        
        // Enable the selected one
        when (iconType) {
            PrivacySettings.ICON_DEFAULT -> enableComponent(packageManager, MAIN_ACTIVITY)
            PrivacySettings.ICON_CALCULATOR -> enableComponent(packageManager, ALIAS_CALCULATOR)
            PrivacySettings.ICON_NOTES -> enableComponent(packageManager, ALIAS_NOTES)
            PrivacySettings.ICON_FILE -> enableComponent(packageManager, ALIAS_FILE)
        }
    }
    
    private fun enableComponent(pm: PackageManager, componentName: String) {
        pm.setComponentEnabledSetting(
            ComponentName(context, componentName),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }
    
    private fun disableComponent(pm: PackageManager, componentName: String) {
        pm.setComponentEnabledSetting(
            ComponentName(context, componentName),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
