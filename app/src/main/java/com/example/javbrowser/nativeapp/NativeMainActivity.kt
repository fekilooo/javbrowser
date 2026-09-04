package com.example.javbrowser.nativeapp

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.javbrowser.nativeapp.data.JavRepository
import com.example.javbrowser.nativeapp.data.LibraryStore
import com.example.javbrowser.nativeapp.source.*
import com.example.javbrowser.nativeapp.ui.NativeJavApp

class NativeMainActivity : ComponentActivity() {
    private val repository by lazy { JavRepository(JavSourceManager(listOf(JavDbSource(), MissAvSource(), JableSource()))) }
    private val library by lazy { LibraryStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyPrivacy()
        setContent { NativeJavApp(repository, library, incomingText(intent)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent { NativeJavApp(repository, library, incomingText(intent)) }
    }

    private fun applyPrivacy() {
        val secure = getSharedPreferences("native_settings", MODE_PRIVATE).getBoolean("secure_screen", true)
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun incomingText(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        else -> null
    }
}
