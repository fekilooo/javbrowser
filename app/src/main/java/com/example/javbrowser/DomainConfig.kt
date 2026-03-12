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
    
    fun getRouVideoDomain(): String = adFilterRules.getDomains()["rou_video"] ?: "rouva2.xyz"

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
            } else if (host.contains("rou.video", ignoreCase = true) || host.contains("rouva2.", ignoreCase = true)) {
                return uri.buildUpon().authority(getRouVideoDomain()).build().toString()
            }
        } catch (e: Exception) {
            // 解析失敗則直接回傳原網址
        }
        return url
    }
}
