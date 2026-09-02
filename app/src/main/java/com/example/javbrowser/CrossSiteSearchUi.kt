package com.example.javbrowser

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Toast
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.net.URLEncoder

/** 共用於新着列表與影片內容頁的跨站搜尋浮框。 */
object CrossSiteSearchUi {
    private val CODE_PATTERN = Regex(
        "\\b(?:FC2(?:[-_ ]?PPV)?[-_ ]?\\d{5,10}|[A-Z]{2,10}[-_ ]?[A-Z0-9]{1,8}[-_ ]?\\d{2,6})\\b",
        RegexOption.IGNORE_CASE
    )

    fun extractCode(text: String): String = CODE_PATTERN.find(text)?.value
        ?.replace(Regex("\\s+"), "-")?.replace('_', '-')?.uppercase().orEmpty()

    fun show(context: Context, rawCode: String) {
        val code = extractCode(rawCode).ifBlank { rawCode.trim().uppercase() }
        if (code.isBlank()) return
        val domain = DomainConfig(AdFilterRules(context.applicationContext))
        val encoded = Uri.encode(code)
        val query = URLEncoder.encode(code, "UTF-8")
        val targets = listOf(
            "MissAV" to domain.getMissAvSearchUrl(encoded),
            "Jable.TV" to "https://jable.tv/search/$encoded/",
            "AvJoy" to "https://${domain.getAvJoyDomain()}/search/videos/$encoded",
            "PigAV" to "https://pigav.ws/search?search=$query&searchTarget=local",
            "AVToday" to "https://avtoday.io/search?s=$query",
            "JavHDPorn" to "https://www.javhdporn.net/?s=$query",
            "7MMTV" to domain.get7MmTvSearchUrl(code),
            "Avple" to domain.getAvpleSearchUrl(code),
            "Whos.tv" to domain.getWhosSearchUrl(code)
        )
        val dialog = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 16))
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 28, 32))
                val radius = dp(context, 22).toFloat()
                setCornerRadii(floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f))
            }
        }

        val handle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(125, 125, 132))
                cornerRadius = dp(context, 3).toFloat()
            }
        }
        root.addView(handle, LinearLayout.LayoutParams(dp(context, 42), dp(context, 4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(context, 10)
        })

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "🔍 跨站搜尋\n$code"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val copyButton = MaterialButton(context).apply {
            text = "複製番號"
            setTextSize(12f)
            setAllCaps(false)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(92, 56, 130))
            cornerRadius = dp(context, 8)
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("番號", code))
                Toast.makeText(context, "已複製 $code", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(copyButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 38)).apply {
            marginEnd = dp(context, 6)
        })

        val closeButton = MaterialButton(context).apply {
            text = "×"
            setTextSize(22f)
            setAllCaps(false)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(92, 56, 130))
            cornerRadius = dp(context, 8)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(context, 42), dp(context, 38)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 12)
        })

        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        targets.chunked(2).forEach { rowTargets ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowTargets.forEach { (name, url) ->
                val targetButton = MaterialButton(context).apply {
                    text = name
                    setTextSize(14f)
                    setAllCaps(false)
                    minHeight = 0
                    minimumHeight = 0
                    insetTop = 0
                    insetBottom = 0
                    setPadding(dp(context, 4), 0, dp(context, 4), 0)
                    setTextColor(Color.rgb(25, 18, 32))
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(187, 134, 252))
                    cornerRadius = dp(context, 10)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener {
                        dialog.dismiss()
                        navigate(context, url)
                    }
                }
                row.addView(targetButton, LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply {
                    marginEnd = dp(context, 4)
                })
            }
            repeat(2 - rowTargets.size) {
                row.addView(Space(context), LinearLayout.LayoutParams(0, dp(context, 48), 1f).apply {
                    marginEnd = dp(context, 4)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48)).apply {
                bottomMargin = dp(context, 6)
            })
        }
        root.addView(grid)

        root.addView(TextView(context).apply {
            text = "點擊網站名稱即可用 $code 搜尋"
            setTextColor(Color.rgb(175, 175, 182))
            textSize = 12f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(context, 2)
        })

        dialog.setContentView(root)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (sheet != null) {
                sheet.setBackgroundColor(Color.TRANSPARENT)
                BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    private fun navigate(context: Context, url: String) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(
            Intent(MainActivity.ACTION_LOAD_URL).putExtra(MainActivity.EXTRA_URL, url)
        )

        // 主圖示會由 AppIconManager 在 MainActivity 與多個 alias 之間切換；
        // 被停用的 MainActivity 元件不能再用 Intent(context, MainActivity::class.java)
        // 直接啟動，否則 Android 會拋出 ActivityNotFoundException，並把使用者留在首頁。
        // 由 PackageManager 取得目前啟用的 launcher component，才能回到既有主頁並載入網址。
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent == null) {
            Toast.makeText(context, "找不到 APP 主頁入口", Toast.LENGTH_SHORT).show()
            return
        }
        launchIntent.putExtra(MainActivity.EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { context.startActivity(launchIntent) }
            .onFailure {
                Toast.makeText(context, "無法開啟搜尋頁", Toast.LENGTH_SHORT).show()
            }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
