package com.example.javbrowser

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var switchLock: SwitchCompat
    private lateinit var radioGroup: RadioGroup
    private lateinit var btnBack: Button
    private lateinit var privacySettings: PrivacySettings
    private lateinit var appIconManager: AppIconManager
    private lateinit var biometricHelper: BiometricHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and hide content in recent apps
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)

        privacySettings = PrivacySettings(this)
        appIconManager = AppIconManager(this)
        biometricHelper = BiometricHelper(this, privacySettings)

        switchLock = findViewById(R.id.switch_lock)
        radioGroup = findViewById(R.id.radio_group_icon)
        btnBack = findViewById(R.id.btn_back)

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        // Load lock setting
        switchLock.isChecked = privacySettings.isLockEnabled

        // Load icon setting
        when (privacySettings.selectedIcon) {
            PrivacySettings.ICON_DEFAULT -> findViewById<RadioButton>(R.id.radio_default).isChecked = true
            PrivacySettings.ICON_CALCULATOR -> findViewById<RadioButton>(R.id.radio_calculator).isChecked = true
            PrivacySettings.ICON_NOTES -> findViewById<RadioButton>(R.id.radio_notes).isChecked = true
            PrivacySettings.ICON_FILE -> findViewById<RadioButton>(R.id.radio_file).isChecked = true
        }
    }

    private fun setupListeners() {
        // Lock switch
        switchLock.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Test biometric availability
                if (biometricHelper.canAuthenticate()) {
                    biometricHelper.authenticate(
                        onSuccess = {
                            privacySettings.isLockEnabled = true
                            privacySettings.updateUnlockTime()
                            Toast.makeText(this, "應用鎖已啟用", Toast.LENGTH_SHORT).show()
                        },
                        onError = { error ->
                            switchLock.isChecked = false
                            Toast.makeText(this, "驗證失敗: $error", Toast.LENGTH_SHORT).show()
                        }
                    )
                } else {
                    switchLock.isChecked = false
                    Toast.makeText(this, "此裝置不支援生物識別", Toast.LENGTH_LONG).show()
                }
            } else {
                privacySettings.isLockEnabled = false
                Toast.makeText(this, "應用鎖已停用", Toast.LENGTH_SHORT).show()
            }
        }

        // Icon selection
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selectedIcon = when (checkedId) {
                R.id.radio_default -> PrivacySettings.ICON_DEFAULT
                R.id.radio_calculator -> PrivacySettings.ICON_CALCULATOR
                R.id.radio_notes -> PrivacySettings.ICON_NOTES
                R.id.radio_file -> PrivacySettings.ICON_FILE
                else -> PrivacySettings.ICON_DEFAULT
            }

            if (selectedIcon != privacySettings.selectedIcon) {
                showIconChangeDialog(selectedIcon)
            }
        }

        // Back button
        btnBack.setOnClickListener {
            finish()
        }

        // Set PIN button
        findViewById<Button>(R.id.btn_set_pin).setOnClickListener {
            showSetPinDialog()
        }
    }

    private fun showSetPinDialog() {
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        input.filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        input.hint = "Enter 4-6 digit PIN"
        
        // Add padding
        val container = android.widget.FrameLayout(this)
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.leftMargin = 50
        params.rightMargin = 50
        input.layoutParams = params
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Set PIN Code")
            .setMessage("Enter a backup PIN code (4-6 digits)")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..6) {
                    privacySettings.pinCode = pin
                    Toast.makeText(this, "PIN Code saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PIN must be 4-6 digits", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIconChangeDialog(newIcon: String) {
        val iconName = when (newIcon) {
            PrivacySettings.ICON_DEFAULT -> "JAV Browser"
            PrivacySettings.ICON_CALCULATOR -> "Calculator"
            PrivacySettings.ICON_NOTES -> "Notes"
            PrivacySettings.ICON_FILE -> "File Manager"
            else -> "JAV Browser"
        }

        AlertDialog.Builder(this)
            .setTitle("更換應用圖標")
            .setMessage("確定要將圖標更換為「$iconName」嗎？\n\n舊圖標會從桌面消失，新圖標會出現。")
            .setPositiveButton("確定") { _, _ ->
                appIconManager.switchIcon(newIcon)
                privacySettings.selectedIcon = newIcon
                Toast.makeText(this, "圖標已更換，請在桌面尋找新圖標", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消") { _, _ ->
                loadSettings() // Revert selection
            }
            .show()
    }
}
