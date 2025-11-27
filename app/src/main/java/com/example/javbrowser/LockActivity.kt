package com.example.javbrowser

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LockActivity : AppCompatActivity() {

    private lateinit var privacySettings: PrivacySettings
    private lateinit var biometricHelper: BiometricHelper
    private lateinit var tvPinDisplay: TextView
    private var currentPinInput = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and hide content in recent apps
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        
        privacySettings = PrivacySettings(this)
        
        // Set activity title and task description based on privacy mode
        title = privacySettings.currentAppLabel
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            setTaskDescription(
                android.app.ActivityManager.TaskDescription(
                    privacySettings.currentAppLabel,
                    privacySettings.currentIconResourceId
                )
            )
        } else {
            @Suppress("DEPRECATION")
            setTaskDescription(
                android.app.ActivityManager.TaskDescription(
                    privacySettings.currentAppLabel,
                    android.graphics.BitmapFactory.decodeResource(resources, privacySettings.currentIconResourceId)
                )
            )
        }
        
        setContentView(R.layout.activity_lock)


        biometricHelper = BiometricHelper(this, privacySettings)
        tvPinDisplay = findViewById(R.id.tv_pin_display)

        // Set icon based on privacy mode
        val iconView = findViewById<android.widget.ImageView>(R.id.iv_lock_icon)
        iconView.setImageResource(privacySettings.currentIconResourceId)

        setupPinPad()
        setupBiometricButton()

        // Auto-start biometric if available
        if (biometricHelper.canAuthenticate()) {
            startBiometricAuth()
        }
    }

    private fun setupPinPad() {
        val pinButtons = listOf(
            R.id.grid_pin to "0", // This is the container, need to iterate children or find by ID
        )
        
        // Find all number buttons
        val gridLayout = findViewById<android.widget.GridLayout>(R.id.grid_pin)
        for (i in 0 until gridLayout.childCount) {
            val view = gridLayout.getChildAt(i)
            if (view is Button && view.tag != null) {
                view.setOnClickListener {
                    appendPinDigit(view.tag.toString())
                }
            }
        }

        findViewById<ImageButton>(R.id.btn_delete).setOnClickListener {
            if (currentPinInput.isNotEmpty()) {
                currentPinInput.deleteCharAt(currentPinInput.length - 1)
                updatePinDisplay()
            }
        }

        findViewById<ImageButton>(R.id.btn_enter).setOnClickListener {
            verifyPin()
        }
        
        updatePinDisplay()
    }

    private fun appendPinDigit(digit: String) {
        if (currentPinInput.length < 6) {
            currentPinInput.append(digit)
            updatePinDisplay()
        }
    }

    private fun updatePinDisplay() {
        val sb = StringBuilder()
        for (i in currentPinInput.indices) {
            sb.append("•")
        }
        if (sb.isEmpty()) {
            tvPinDisplay.text = "Enter PIN"
            tvPinDisplay.textSize = 24f
        } else {
            tvPinDisplay.text = sb.toString()
            tvPinDisplay.textSize = 32f
        }
    }

    private fun verifyPin() {
        val input = currentPinInput.toString()
        if (privacySettings.validatePin(input)) {
            unlockApp()
        } else {
            Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            currentPinInput.clear()
            updatePinDisplay()
            
            // Shake animation or vibration could be added here
        }
    }

    private fun setupBiometricButton() {
        findViewById<Button>(R.id.btn_use_biometric).setOnClickListener {
            startBiometricAuth()
        }
    }

    private fun startBiometricAuth() {
        biometricHelper.authenticate(
            onSuccess = {
                unlockApp()
            },
            onError = {
                // Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun unlockApp() {
        privacySettings.updateUnlockTime()
        setResult(RESULT_OK)
        finish()
        // Disable animation for smoother transition
        overridePendingTransition(0, 0)
    }

    override fun onBackPressed() {
        // Prevent going back to the app content
        // Minimize the app instead
        moveTaskToBack(true)
    }
}
