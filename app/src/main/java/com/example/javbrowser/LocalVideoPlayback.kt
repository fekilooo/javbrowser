package com.example.javbrowser

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.view.View
import androidx.appcompat.app.AlertDialog

object LocalVideoPlayback {
    private const val DOWNLOAD_PREFS = "download_playback"
    private const val PLAYER_CHOICE = "player_choice"

    fun openDownload(activity: Activity, uri: Uri, title: String, chooseAgain: Boolean = false) {
        if (uri.scheme !in setOf("content", "file")) {
            Toast.makeText(activity, LanguageManager.text(activity, "無法讀取本機影片網址", "Invalid local video address"), Toast.LENGTH_LONG).show()
            return
        }
        val prefs = activity.getSharedPreferences(DOWNLOAD_PREFS, Activity.MODE_PRIVATE)
        val saved = DownloadPlayerChoice.fromStored(prefs.getString(PLAYER_CHOICE, null))
        when (saved.forPlayback(chooseAgain)) {
            DownloadPlayerChoice.INTERNAL -> launchInternal(activity, uri, title)
            DownloadPlayerChoice.EXTERNAL -> launchExternal(activity, uri)
            DownloadPlayerChoice.ASK -> {
                val padding = (20 * activity.resources.displayMetrics.density).toInt()
                val group = RadioGroup(activity)
                val internal = RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = LanguageManager.text(activity, "內建播放器", "Internal player")
                }
                val external = RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = LanguageManager.text(activity, "其他 App（系統選擇器）", "Other apps (system chooser)")
                }
                group.addView(internal)
                group.addView(external)
                group.check(if (saved == DownloadPlayerChoice.EXTERNAL) external.id else internal.id)
                val remember = CheckBox(activity).apply {
                    text = LanguageManager.text(activity, "記住我的選擇（下載管理）", "Remember my choice (downloads)")
                }
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, 0, padding, 0)
                    addView(group)
                    addView(remember)
                    addView(TextView(activity).apply {
                        text = LanguageManager.text(activity,
                            "內建播放器若不支援影片格式，可返回改用其他 App。之後可按播放旁的圖示重新選擇。",
                            "If the internal player cannot play this format, go back and choose another app. Use the icon beside Play to choose again.")
                        textSize = 12f
                        setPadding(0, padding / 2, 0, 0)
                    })
                }
                AlertDialog.Builder(activity)
                    .setTitle(LanguageManager.text(activity, "選擇播放器", "Choose player"))
                    .setView(content)
                    .setPositiveButton(LanguageManager.text(activity, "播放", "Play")) { _, _ ->
                        val choice = if (group.checkedRadioButtonId == internal.id) DownloadPlayerChoice.INTERNAL else DownloadPlayerChoice.EXTERNAL
                        prefs.edit().putString(PLAYER_CHOICE, choice.remembered(remember.isChecked).name).apply()
                        if (choice == DownloadPlayerChoice.INTERNAL) launchInternal(activity, uri, title)
                        else launchExternal(activity, uri)
                    }
                    .setNeutralButton(LanguageManager.text(activity, "恢復每次詢問", "Always ask")) { _, _ ->
                        prefs.edit().remove(PLAYER_CHOICE).apply()
                        Toast.makeText(activity, LanguageManager.text(activity, "已恢復每次詢問播放器", "Player selection will appear each time"), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(LanguageManager.text(activity, "取消", "Cancel"), null)
                    .show()
            }
        }
    }

    private fun launchInternal(activity: Activity, uri: Uri, title: String) {
        val intent = Intent(activity, FullscreenInternalPlayerActivity::class.java).apply {
            putExtra(FullscreenInternalPlayerActivity.EXTRA_VIDEO_URL, uri.toString())
            putExtra(FullscreenInternalPlayerActivity.EXTRA_LOCAL_PLAYBACK, true)
            putExtra(FullscreenInternalPlayerActivity.EXTRA_DOWNLOAD_NAME, title)
            clipData = ClipData.newRawUri("video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, LanguageManager.text(activity, "無法啟動內建播放器，請改選其他 App", "Unable to start the internal player; choose another app"), Toast.LENGTH_LONG).show()
        }
    }

    fun openExternal(activity: Activity, uri: Uri) {
        launchExternal(activity, uri)
    }

    private fun launchExternal(activity: Activity, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            clipData = ClipData.newRawUri("video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(intent, LanguageManager.text(activity, "選擇播放器", "Choose player")))
        }.onFailure {
            Toast.makeText(activity, LanguageManager.text(activity, "找不到可播放此檔案的應用程式", "No app can play this file"), Toast.LENGTH_LONG).show()
        }
    }
}
