package com.example.javbrowser

/**
 * 網域設定管理器
 * 
 * 從 AdFilterRules 讀取雲端更新的網域設定，
 * 讓 APP 不需要重新發版就能切換被 DNS 污染的網域。
 * 
 * 使用方式：
 *   val domainConfig = DomainConfig(adFilterRules)
 *   domainConfig.getMissAvBaseUrl()          // https://missav.ws/
 *   domainConfig.getMissAvSearchUrl("ABP-123") // https://missav.ws/search/ABP-123
 */
class DomainConfig(private val adFilterRules: AdFilterRules) {

    companion object {
        // 預設網域（當雲端設定讀取失敗時的 fallback）
        const val DEFAULT_MISSAV_DOMAIN = "missav.ws"
        const val DEFAULT_7MMTV_DOMAIN = "7mmtv.sx"
        const val DEFAULT_AVPLE_DOMAIN = "avple.tv"
        const val DEFAULT_WHOS_DOMAIN = "whos.tv"
    }

    /**
     * 取得目前有效的 MissAV 網域（純網域，不含 https://）
     * 例如：missav.ws、missav.com
     */
    fun getMissAvDomain(): String {
        return adFilterRules.getDomains()["missav"] ?: DEFAULT_MISSAV_DOMAIN
    }

    /**
     * 取得 MissAV 首頁完整 URL
     * 例如：https://missav.ws/
     */
    fun getMissAvBaseUrl(): String = "https://${getMissAvDomain()}/"

    /**
     * 取得 MissAV 搜尋完整 URL
     * 例如：https://missav.ws/search/ABP-123
     */
    fun getMissAvSearchUrl(query: String): String =
        "https://${getMissAvDomain()}/search/${query}"

    fun getJableDomain(): String = adFilterRules.getDomains()["jable"] ?: "jable.tv"

    fun getRouVideoDomain(): String {
        val configured = adFilterRules.getDomains()["rou_video"].orEmpty()
        // 已安裝版本可能仍快取 rouva1~rouva7；新版站點已宣告 rouva8 為目前網域。
        return if (Regex("^rouva[1-7]\\.xyz$", RegexOption.IGNORE_CASE).matches(configured)) {
            "rouva8.xyz"
        } else {
            configured.ifBlank { "rouva8.xyz" }
        }
    }

    fun getAvJoyDomain(): String = adFilterRules.getDomains()["avjoy"] ?: "avjoy.me"

    fun get7MmTvDomain(): String =
        adFilterRules.getDomains()["7mmtv"] ?: DEFAULT_7MMTV_DOMAIN

    fun getAvpleDomain(): String =
        adFilterRules.getDomains()["avple"] ?: DEFAULT_AVPLE_DOMAIN

    fun getWhosDomain(): String =
        adFilterRules.getDomains()["whos"] ?: DEFAULT_WHOS_DOMAIN

    fun get7MmTvBaseUrl(): String = "https://${get7MmTvDomain()}/"

    /** 7MMTV 的搜尋表單會 POST，但送出後會導向可重複使用的 GET 結果頁。 */
    fun get7MmTvSearchUrl(query: String): String =
        "${get7MmTvBaseUrl().trimEnd('/')}/zh/searchall_search/all/${android.net.Uri.encode(query)}/1.html"

    fun getAvpleBaseUrl(): String = "https://${getAvpleDomain()}/"

    fun getAvpleSearchUrl(query: String): String =
        getAvpleBaseUrl().trimEnd('/') + "/search?key=" +
            android.net.Uri.encode(query)

    fun getWhosBaseUrl(): String = "https://${getWhosDomain()}/"

    fun getWhosSearchUrl(query: String): String =
        getWhosBaseUrl().trimEnd('/') + "/result?search=" +
            android.net.Uri.encode(query)

    /**
     * 更新 URL 中的網域為最新網域 (如果是已知的被封鎖網域)
     * 主要用於：書籤載入、歷史紀錄等，確保讀取的舊網址自動替換為最新有效網域
     */
    fun updateUrlIfNeeded(url: String): String {
        try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host ?: return url

            if (host.contains("missav.", ignoreCase = true)) {
                return uri.buildUpon().authority(getMissAvDomain()).build().toString()
            } else if (host.contains("jable.", ignoreCase = true)) {
                return uri.buildUpon().authority(getJableDomain()).build().toString()
            } else if (host.contains("rou.video", ignoreCase = true) || host.contains("rouva", ignoreCase = true)) {
                return uri.buildUpon().authority(getRouVideoDomain()).build().toString()
            } else if (host.contains("avjoy.", ignoreCase = true)) {
                return uri.buildUpon().authority(getAvJoyDomain()).build().toString()
            } else if (host.contains("7mmtv", ignoreCase = true) ||
                host.contains("7tv", ignoreCase = true)) {
                return uri.buildUpon().authority(get7MmTvDomain()).build().toString()
            } else if (host.contains("avple", ignoreCase = true)) {
                return uri.buildUpon().authority(getAvpleDomain()).build().toString()
            } else if (host.contains("whos", ignoreCase = true)) {
                return uri.buildUpon().authority(getWhosDomain()).build().toString()
            }
        } catch (e: Exception) {
            // 解析失敗則直接回傳原網址
        }
        return url
    }
}
