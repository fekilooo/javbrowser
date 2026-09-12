package com.example.javbrowser

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DownloadsActivity : LocalizedActivity() {

    private lateinit var adapter: DownloadsAdapter
    private lateinit var storageHeaderAdapter: StorageHeaderAdapter
    private lateinit var emptyView: TextView
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val isScanning = AtomicBoolean(false)

    private val changeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val folderName = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: "自訂資料夾"
            DownloadRepository.setStorageTree(this, uri, "自訂：$folderName")
            refresh()
            scanLocalFiles()
            Toast.makeText(this, LanguageManager.text(this, "下載位置已更新", "Download location updated"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, LanguageManager.text(this, "無法取得資料夾寫入權限", "Unable to access the selected folder"), Toast.LENGTH_LONG).show()
        }
    }

    private val importVideos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importVideoUris(uris, persistPermission = true)
    }

    private val requestVideoPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        scanLocalFiles(showToast = true)
        if (!LocalVideoRepository.hasVideoLibraryAccess(this)) {
            Toast.makeText(
                this,
                LanguageManager.text(
                    this,
                    "未取得影片權限，只能顯示 App 自己建立或手動匯入的影片",
                    "Video access was not granted; only app-created or manually imported videos are available"
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PrivacySettings(this).isScreenSecure) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        setContentView(R.layout.activity_downloads)

        emptyView = findViewById(R.id.tv_downloads_empty)
        storageHeaderAdapter = StorageHeaderAdapter(
            onChooseFolder = {
                chooseFolder.launch(DownloadRepository.storageTreeUri(this))
            },
            onImportFiles = {
                importVideos.launch(arrayOf("video/*"))
            },
            onResetFolder = {
                DownloadRepository.setStorageTree(this, null, null)
                refresh()
                scanLocalFiles()
                Toast.makeText(
                    this,
                    LanguageManager.text(this, "已恢復系統下載資料夾", "Default download folder restored"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        adapter = DownloadsAdapter(
            onOpen = ::openDownload,
            onChoosePlayer = { openDownload(it, chooseAgain = true) },
            onCancel = ::cancelDownload,
            onRetry = ::retryDownload,
            onDelete = ::confirmDelete
        )
        findViewById<RecyclerView>(R.id.rv_downloads).apply {
            layoutManager = LinearLayoutManager(this@DownloadsActivity)
            adapter = ConcatAdapter(storageHeaderAdapter, this@DownloadsActivity.adapter)
        }

        findViewById<Button>(R.id.btn_downloads_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_pc_recording_library).apply {
            text = LanguageManager.text(
                this@DownloadsActivity,
                "PC 錄影庫  ·  掃描與播放電腦錄影",
                "PC recording library  ·  Scan and play PC recordings"
            )
            setOnClickListener {
                startActivity(Intent(this@DownloadsActivity, PcRecordingLibraryActivity::class.java))
            }
        }
        findViewById<Button>(R.id.btn_clear_download_records).setOnClickListener {
            requestVideoAccessAndScan(showToast = true)
        }
        applyEnglishCompactLayout()
        refresh()
        requestVideoAccessAndScan()
        handleIncomingVideoIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingVideoIntent(intent)
    }

    private fun applyEnglishCompactLayout() {
        if (!LanguageManager.isEnglish(this)) return
        findViewById<TextView>(R.id.tv_downloads_title).textSize = 19f
        listOf(
            R.id.btn_downloads_back,
            R.id.btn_clear_download_records,
            R.id.btn_pc_recording_library
        ).forEach { id -> compactButton(findViewById(id), 11f) }
    }

    private fun compactButton(button: Button, textSize: Float) {
        val horizontal = (10 * resources.displayMetrics.density).toInt()
        val height = (40 * resources.displayMetrics.density).toInt()
        button.isAllCaps = false
        button.textSize = textSize
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.layoutParams = button.layoutParams.apply { this.height = height }
        button.setPadding(horizontal, 0, horizontal, 0)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            changeReceiver,
            IntentFilter(DownloadRepository.ACTION_DOWNLOADS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refresh()
    }

    override fun onStop() {
        runCatching { unregisterReceiver(changeReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        scanExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        val records = DownloadRepository.list(this)
        adapter.submit(records, FavoritesManager(this).getFavorites())
        emptyView.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        updateStorageSummary(records)
    }

    private fun updateStorageSummary(records: List<VideoDownloadRecord>) {
        val localFiles = records
            .filter { it.fileSizeBytes > 0L || !it.fileUri.isNullOrBlank() }
            .distinctBy { it.fileUri ?: it.fileName ?: it.id }
        val filesBytes = localFiles.sumOf { record ->
            record.fileSizeBytes.takeIf { it > 0L }
                ?: record.fileUri?.let { uri ->
                    runCatching { LocalVideoRepository.querySize(this, Uri.parse(uri)) }.getOrDefault(0L)
                }
                ?: 0L
        }
        val storage = StatFs(Environment.getExternalStorageDirectory().absolutePath)
        val totalBytes = storage.totalBytes.coerceAtLeast(0L)
        val availableBytes = storage.availableBytes.coerceAtLeast(0L)
        val usagePercent = if (totalBytes > 0L) filesBytes * 100.0 / totalBytes else 0.0

        val percentLabel = String.format(Locale.US, "%.2f%%", usagePercent)
        storageHeaderAdapter.submit(
            StorageHeaderUi(
                location = LanguageManager.storageLocation(
                    this,
                    DownloadRepository.storageDisplayName(this)
                ),
                files = LanguageManager.text(
                    this,
                    "App 影片 ${localFiles.size} 個 · ${formatStorageSize(filesBytes)} · 佔手機 $percentLabel",
                    "App videos ${localFiles.size} · ${formatStorageSize(filesBytes)} · $percentLabel of phone"
                ),
                phoneFree = LanguageManager.text(
                    this,
                    "手機總容量 ${formatStorageSize(totalBytes)} · 剩餘 ${formatStorageSize(availableBytes)} · 刪除可釋放 ${formatStorageSize(filesBytes)}",
                    "Phone ${formatStorageSize(totalBytes)} · Free ${formatStorageSize(availableBytes)} · Delete to free ${formatStorageSize(filesBytes)}"
                ),
                usageProgress = (usagePercent * 10.0).toInt().coerceIn(0, 1000)
            )
        )
    }

    private fun formatStorageSize(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        val gib = safeBytes / (1024.0 * 1024.0 * 1024.0)
        return if (safeBytes >= 1024L * 1024L * 1024L) {
            String.format(Locale.US, "%.2f GB", gib)
        } else {
            String.format(Locale.US, "%.1f MB", safeBytes / (1024.0 * 1024.0))
        }
    }

    private fun scanLocalFiles(showToast: Boolean = false) {
        if (!isScanning.compareAndSet(false, true)) return
        scanExecutor.execute {
            try {
                val files = LocalVideoRepository.scan(this)
                DownloadRepository.syncLocalFiles(
                    this,
                    files,
                    markMissing = true,
                    pruneUnmanaged = true
                )
                if (!isDestroyed) {
                    runOnUiThread {
                        refresh()
                        if (showToast) {
                            Toast.makeText(this, LanguageManager.text(this, "已找到 ${files.size} 個本地影片", "Found ${files.size} local videos"), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } finally {
                isScanning.set(false)
            }
        }
    }

    private fun requestVideoAccessAndScan(showToast: Boolean = false) {
        if (LocalVideoRepository.hasVideoLibraryAccess(this) || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            scanLocalFiles(showToast)
            return
        }
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        requestVideoPermission.launch(permission)
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingVideoIntent(incoming: Intent?) {
        if (incoming == null) return
        val uris = when (incoming.action) {
            Intent.ACTION_VIEW -> listOfNotNull(incoming.data)
            Intent.ACTION_SEND -> listOfNotNull(incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_SEND_MULTIPLE -> incoming
                .getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                .orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        importVideoUris(
            uris,
            persistPermission = incoming.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        )
        incoming.action = null
    }

    private fun importVideoUris(uris: Collection<Uri>, persistPermission: Boolean) {
        val uniqueUris = uris.distinctBy(Uri::toString)
        if (uniqueUris.isEmpty()) return
        if (persistPermission) {
            uniqueUris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        }
        scanExecutor.execute {
            val files = LocalVideoRepository.importUris(this, uniqueUris)
            if (files.isNotEmpty()) DownloadRepository.syncLocalFiles(this, files)
            if (!isDestroyed) {
                runOnUiThread {
                    refresh()
                    val message = if (files.isNotEmpty()) {
                        LanguageManager.text(
                            this,
                            "已匯入 ${files.size} 個影片",
                            "Imported ${files.size} videos"
                        )
                    } else {
                        LanguageManager.text(
                            this,
                            "無法讀取影片；超級保密櫃內的檔案無法匯入",
                            "Unable to read the videos; files in Super Vault cannot be imported"
                        )
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openDownload(record: VideoDownloadRecord) = openDownload(record, chooseAgain = false)

    private fun openDownload(record: VideoDownloadRecord, chooseAgain: Boolean) {
        val uri = record.fileUri?.let(Uri::parse) ?: return
        LocalVideoPlayback.openDownload(this, uri, record.fileName ?: record.title, chooseAgain)
    }

    private fun cancelDownload(record: VideoDownloadRecord) {
        val intent = Intent(this, VideoDownloadService::class.java).apply {
            action = VideoDownloadService.ACTION_CANCEL
            putExtra(VideoDownloadService.EXTRA_DOWNLOAD_ID, record.id)
        }
        startService(intent)
    }

    private fun retryDownload(record: VideoDownloadRecord) {
        if (record.status == DownloadRepository.STATUS_DOWNLOADING ||
            record.status == DownloadRepository.STATUS_PENDING
        ) {
            cancelDownload(record)
        }
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookieCandidates = listOf(record.cookieSourceUrl, record.sourceUrl, record.referer)
            .filter { it.isNotBlank() }
            .distinct()
        val cookieMatch = cookieCandidates.firstNotNullOfOrNull { url ->
            cookieManager.getCookie(url)?.takeIf { it.isNotBlank() }?.let { url to it }
        }
        val cookieSourceUrl = cookieMatch?.first ?: record.cookieSourceUrl
        val cookies = cookieMatch?.second.orEmpty()
        val retryRecord = DownloadRepository.create(
            this,
            record.title,
            record.sourceUrl,
            record.referer,
            cookieSourceUrl
        )
        DownloadRepository.remove(this, record.id)
        val intent = Intent(this, VideoDownloadService::class.java).apply {
            action = VideoDownloadService.ACTION_START
            putExtra(VideoDownloadService.EXTRA_URL, record.sourceUrl)
            putExtra(VideoDownloadService.EXTRA_REFERER, record.referer)
            putExtra(VideoDownloadService.EXTRA_COOKIES, cookies)
            putExtra(VideoDownloadService.EXTRA_COOKIE_SOURCE_URL, cookieSourceUrl)
            putExtra(VideoDownloadService.EXTRA_FILE_NAME, record.title)
            putExtra(VideoDownloadService.EXTRA_DOWNLOAD_ID, retryRecord.id)
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, LanguageManager.text(this, "已重新加入下載", "Added to download queue again"), Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(record: VideoDownloadRecord) {
        val deleteFile = record.status == DownloadRepository.STATUS_COMPLETED && record.fileUri != null
        AlertDialog.Builder(this)
            .setTitle(LanguageManager.text(this, if (deleteFile) "刪除影片" else "刪除紀錄", if (deleteFile) "Delete Video" else "Delete Record"))
            .setMessage(LanguageManager.text(this, if (deleteFile) "將刪除已下載的影片檔案與紀錄。" else "將移除此下載紀錄。", if (deleteFile) "Delete the video file and its record?" else "Remove this download record?"))
            .setPositiveButton(LanguageManager.text(this, "刪除", "Delete")) { _, _ -> deleteRecord(record, deleteFile) }
            .setNegativeButton(LanguageManager.text(this, "取消", "Cancel"), null)
            .show()
    }

    private fun deleteRecord(record: VideoDownloadRecord, deleteFile: Boolean) {
        if (deleteFile) {
            record.fileUri?.let { uriString ->
                runCatching {
                    val uri = Uri.parse(uriString)
                    if (uri.scheme == "content") contentResolver.delete(uri, null, null)
                    else if (uri.scheme == "file") java.io.File(uri.path.orEmpty()).delete()
                    Unit
                }
            }
        }
        DownloadRepository.remove(this, record.id)
    }

    private data class StorageHeaderUi(
        val location: String,
        val files: String,
        val phoneFree: String,
        val usageProgress: Int
    )

    private class StorageHeaderAdapter(
        private val onChooseFolder: () -> Unit,
        private val onImportFiles: () -> Unit,
        private val onResetFolder: () -> Unit
    ) : RecyclerView.Adapter<StorageHeaderAdapter.ViewHolder>() {
        private var item = StorageHeaderUi("", "", "", 0)

        fun submit(value: StorageHeaderUi) {
            item = value
            notifyItemChanged(0)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download_storage_header, parent, false)
            return ViewHolder(view)
        }

        override fun getItemCount(): Int = 1

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(item)
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val location: TextView = view.findViewById(R.id.tv_download_storage_location)
            private val files: TextView = view.findViewById(R.id.tv_download_files_total)
            private val usage: ProgressBar = view.findViewById(R.id.progress_download_storage_usage)
            private val phoneFree: TextView = view.findViewById(R.id.tv_download_phone_free)
            private val chooseFolder: Button = view.findViewById(R.id.btn_choose_download_folder)
            private val importFiles: Button = view.findViewById(R.id.btn_import_download_files)
            private val resetFolder: Button = view.findViewById(R.id.btn_reset_download_folder)

            init {
                chooseFolder.text = LanguageManager.text(view.context, "資料夾", "Folder")
                importFiles.text = LanguageManager.text(view.context, "匯入", "Import")
                resetFolder.text = LanguageManager.text(view.context, "預設", "Default")
                chooseFolder.isAllCaps = false
                importFiles.isAllCaps = false
                resetFolder.isAllCaps = false
                chooseFolder.setOnClickListener { onChooseFolder() }
                importFiles.setOnClickListener { onImportFiles() }
                resetFolder.setOnClickListener { onResetFolder() }
            }

            fun bind(value: StorageHeaderUi) {
                location.text = value.location
                files.text = value.files
                usage.progress = value.usageProgress
                phoneFree.text = value.phoneFree
            }
        }
    }

    private class DownloadsAdapter(
        private val onOpen: (VideoDownloadRecord) -> Unit,
        private val onChoosePlayer: (VideoDownloadRecord) -> Unit,
        private val onCancel: (VideoDownloadRecord) -> Unit,
        private val onRetry: (VideoDownloadRecord) -> Unit,
        private val onDelete: (VideoDownloadRecord) -> Unit
    ) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {
        private var items: List<VideoDownloadRecord> = emptyList()
        private var favoritesByCode: Map<String, FavoriteItem> = emptyMap()

        fun submit(records: List<VideoDownloadRecord>, favorites: List<FavoriteItem>) {
            items = records
            favoritesByCode = favorites.mapNotNull { favorite ->
                val code = LocalVideoRepository.normalizeCode(favorite.javCode)
                    ?: LocalVideoRepository.extractJavCode(favorite.title)
                code?.let { it to favorite }
            }.toMap()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false).also {
                    LanguageManager.translateViewTree(parent.context, it)
                }
            )
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.tv_download_title)
            private val cover: android.widget.ImageView = view.findViewById(R.id.iv_download_cover)
            private val info: TextView = view.findViewById(R.id.tv_download_info)
            private val status: TextView = view.findViewById(R.id.tv_download_status)
            private val progress: ProgressBar = view.findViewById(R.id.progress_download)
            private val fileInfo: TextView = view.findViewById(R.id.tv_download_file_info)
            private val location: TextView = view.findViewById(R.id.tv_download_location)
            private val open: Button = view.findViewById(R.id.btn_download_open)
            private val choosePlayer: android.widget.ImageButton = view.findViewById(R.id.btn_download_choose_player)
            private val cancel: Button = view.findViewById(R.id.btn_download_cancel)
            private val retry: Button = view.findViewById(R.id.btn_download_retry)
            private val delete: Button = view.findViewById(R.id.btn_download_delete)

            init {
                if (LanguageManager.isEnglish(view.context)) {
                    title.textSize = 14f
                    info.textSize = 11f
                    status.textSize = 11f
                    fileInfo.textSize = 10f
                    location.textSize = 10f
                    listOf(open, cancel, retry, delete).forEach { button ->
                        val horizontal = (9 * view.resources.displayMetrics.density).toInt()
                        val height = (40 * view.resources.displayMetrics.density).toInt()
                        button.isAllCaps = false
                        button.textSize = 11f
                        button.minWidth = 0
                        button.minimumWidth = 0
                        button.minHeight = 0
                        button.minimumHeight = 0
                        button.layoutParams = button.layoutParams.apply { this.height = height }
                        button.setPadding(horizontal, 0, horizontal, 0)
                    }
                }
            }

            fun bind(record: VideoDownloadRecord) {
                val code = LocalVideoRepository.normalizeCode(record.javCode)
                    ?: LocalVideoRepository.extractJavCode(record.fileName ?: record.title)
                val favorite = code?.let(favoritesByCode::get)
                title.text = favorite?.title ?: record.fileName ?: record.title
                if (!favorite?.thumbnailUrl.isNullOrBlank()) {
                    com.bumptech.glide.Glide.with(cover)
                        .load(favorite?.thumbnailUrl)
                        .centerCrop()
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_gallery)
                        .into(cover)
                } else if (
                    record.status == DownloadRepository.STATUS_COMPLETED &&
                    !record.fileUri.isNullOrBlank()
                ) {
                    com.bumptech.glide.Glide.with(cover)
                        .load(Uri.parse(record.fileUri))
                        .frame(1_000_000L)
                        .centerCrop()
                        .placeholder(android.R.drawable.ic_menu_gallery)
                        .error(R.drawable.ic_launcher_file)
                        .into(cover)
                } else {
                    com.bumptech.glide.Glide.with(cover).clear(cover)
                    cover.setImageResource(R.drawable.ic_launcher_file)
                }
                val details = buildList {
                    code?.let { add(it) }
                    favorite?.actors?.takeIf { it.isNotEmpty() }?.let {
                        add(LanguageManager.text(
                            itemView.context,
                            "演員：${it.take(3).joinToString("、")}",
                            "Cast: ${it.take(3).joinToString(", ")}"
                        ))
                    }
                    favorite?.genres?.takeIf { it.isNotEmpty() }?.let {
                        add(it.take(4).joinToString(", ") { genre ->
                            LanguageManager.genre(itemView.context, genre)
                        })
                    }
                }
                info.text = details.joinToString("\n").ifBlank {
                    LanguageManager.text(itemView.context, "尚未與書籤資料關聯", "Not linked to bookmark metadata")
                }
                val statusLabel = when (record.status) {
                    DownloadRepository.STATUS_PENDING -> LanguageManager.text(itemView.context, "等待中", "Waiting")
                    DownloadRepository.STATUS_DOWNLOADING -> LanguageManager.text(itemView.context, "下載中 ${record.progress}%", "Downloading ${record.progress}%")
                    DownloadRepository.STATUS_COMPLETED -> LanguageManager.text(itemView.context, "已完成", "Completed")
                    DownloadRepository.STATUS_MISSING -> LanguageManager.text(itemView.context, "無法存取", "Unavailable")
                    DownloadRepository.STATUS_CANCELED -> LanguageManager.text(itemView.context, "已取消", "Canceled")
                    else -> LanguageManager.text(itemView.context, "失敗", "Failed")
                }
                val speedLabel = if (
                    record.status == DownloadRepository.STATUS_DOWNLOADING &&
                    record.speedBytesPerSecond > 0L
                ) {
                    " · ${formatSpeed(record.speedBytesPerSecond)}"
                } else {
                    ""
                }
                status.text = "$statusLabel · ${LanguageManager.downloadMessage(itemView.context, record.message)}$speedLabel"
                progress.progress = record.progress
                progress.visibility = if (
                    record.status == DownloadRepository.STATUS_PENDING ||
                    record.status == DownloadRepository.STATUS_DOWNLOADING
                ) View.VISIBLE else View.GONE
                val displayedFileSize = record.fileSizeBytes.takeIf { it > 0L }
                    ?: record.fileUri?.let { uri ->
                        runCatching {
                            LocalVideoRepository.querySize(itemView.context, Uri.parse(uri))
                        }.getOrDefault(0L)
                    }
                    ?: 0L
                fileInfo.text = buildString {
                    if (displayedFileSize > 0L) append("${formatFileSize(displayedFileSize)} · ")
                    append(record.fileName ?: LanguageManager.text(itemView.context, "尚未建立檔案", "File not created yet"))
                }
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(record.createdAt))
                location.text = "$time · ${LanguageManager.storageLocation(itemView.context, record.storageLocation)}"
                open.visibility = if (record.status == DownloadRepository.STATUS_COMPLETED) View.VISIBLE else View.GONE
                choosePlayer.visibility = open.visibility
                choosePlayer.contentDescription = LanguageManager.text(itemView.context, "選擇或變更播放器", "Choose or change player")
                androidx.appcompat.widget.TooltipCompat.setTooltipText(choosePlayer, choosePlayer.contentDescription)
                cancel.visibility = if (
                    record.status == DownloadRepository.STATUS_PENDING ||
                    record.status == DownloadRepository.STATUS_DOWNLOADING
                ) View.VISIBLE else View.GONE
                retry.visibility = if (
                    record.sourceUrl.isNotBlank() && (
                        record.status == DownloadRepository.STATUS_FAILED ||
                        record.status == DownloadRepository.STATUS_CANCELED ||
                        record.status == DownloadRepository.STATUS_DOWNLOADING
                    )
                ) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                retry.text = if (record.status == DownloadRepository.STATUS_DOWNLOADING) {
                    LanguageManager.text(itemView.context, "重連", "Reconnect")
                } else {
                    LanguageManager.text(itemView.context, "接續", "Resume")
                }
                delete.visibility = if (cancel.visibility == View.GONE) View.VISIBLE else View.GONE
                open.setOnClickListener { onOpen(record) }
                choosePlayer.setOnClickListener { onChoosePlayer(record) }
                cancel.setOnClickListener { onCancel(record) }
                retry.setOnClickListener { onRetry(record) }
                delete.setOnClickListener { onDelete(record) }
            }

            private fun formatSpeed(bytesPerSecond: Long): String = when {
                bytesPerSecond >= 1024L * 1024L -> String.format(
                    Locale.US,
                    "%.2f MB/s",
                    bytesPerSecond / (1024.0 * 1024.0)
                )
                bytesPerSecond >= 1024L -> String.format(
                    Locale.US,
                    "%.1f KB/s",
                    bytesPerSecond / 1024.0
                )
                else -> "$bytesPerSecond B/s"
            }

            private fun formatFileSize(bytes: Long): String = when {
                bytes >= 1024L * 1024L * 1024L -> String.format(
                    Locale.US,
                    "%.2f GB",
                    bytes / (1024.0 * 1024.0 * 1024.0)
                )
                bytes >= 1024L * 1024L -> String.format(
                    Locale.US,
                    "%.1f MB",
                    bytes / (1024.0 * 1024.0)
                )
                else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            }
        }
    }
}
