package com.example.javbrowser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class SiteUrlRule(
    val hostEquals: String? = null,
    val hostSuffix: String? = null,
    val pathEquals: String? = null,
    val pathPrefix: String? = null,
    val urlContains: String? = null,
    val mainFrame: Boolean? = null
) {
    fun matches(url: String, isMainFrame: Boolean): Boolean {
        if (mainFrame != null && mainFrame != isMainFrame) return false
        return try {
            val uri = android.net.Uri.parse(url)
            val host = uri.host.orEmpty().lowercase()
            val path = uri.encodedPath.orEmpty()
            val exactHost = hostEquals?.lowercase()
            val suffix = hostSuffix?.trimStart('.')?.lowercase()
            if (exactHost != null && host != exactHost) return false
            if (suffix != null && host != suffix && !host.endsWith(".$suffix")) return false
            if (pathEquals != null && path != pathEquals) return false
            if (pathPrefix != null && !path.startsWith(pathPrefix)) return false
            if (urlContains != null && !url.contains(urlContains, ignoreCase = true)) return false
            true
        } catch (e: Exception) {
            false
        }
    }
}

data class SiteDomRemoveRule(
    val selector: String,
    val closest: String? = null
)

data class SiteAdRuleSet(
    val allowRequests: List<SiteUrlRule> = emptyList(),
    val requestBlock: List<SiteUrlRule> = emptyList(),
    val navigationBlock: List<SiteUrlRule> = emptyList(),
    val domRemove: List<SiteDomRemoveRule> = emptyList()
)

class AdFilterRules(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "ad_filter_rules"
        private const val KEY_RULES_JSON = "rules_json"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_CLOUD_URL = "cloud_url"
        const val DEFAULT_CLOUD_URL = "https://raw.githubusercontent.com/fekilooo/javbrowser/refs/heads/main/ad-filter-rules.json"

        // 雲端尚未升級至 siteRules 時的安全備援；雲端一旦提供 siteRules 即完全以雲端為準。
        private val DEFAULT_SITE_RULES = """
        {
          "avtoday.io": {
            "allowRequests": [
              { "hostSuffix": "avtoday.io", "pathPrefix": "/pic/" },
              { "hostSuffix": "avtoday.io", "pathPrefix": "/preview/" },
              { "hostSuffix": "avtoday.io", "pathPrefix": "/player" },
              { "hostSuffix": "avtoday.io", "pathPrefix": "/streaming/" }
            ],
            "requestBlock": [
              { "hostSuffix": "avtoday.io", "pathEquals": "/redirect-stripchat", "mainFrame": false },
              { "hostSuffix": "avtoday.io", "pathEquals": "/img/500x143-9u.gif", "mainFrame": false }
            ],
            "navigationBlock": [
              { "hostSuffix": "aaa9u.com" },
              { "hostSuffix": "telegram.me" }
            ],
            "domRemove": [
              { "selector": "a[href*=\"aaa9u.com\"]", "closest": "a" },
              { "selector": "img[src*=\"/img/500x143-9u.gif\"]", "closest": "a" },
              { "selector": "iframe[src*=\"/redirect-stripchat\"]", "closest": ".thumbnail.col" },
              { "selector": ".video-title a[href=\"/live\"]", "closest": ".thumbnail.col" },
              { "selector": "a.btn-categories[href*=\"telegram.me\"]", "closest": ".swiper-slide" }
            ]
          },
          "7mmtv.sx": {
            "requestBlock": [
              { "hostSuffix": "creative.19sex.live" },
              { "hostSuffix": "realsrv.com" },
              { "hostSuffix": "labadena.com" },
              { "hostSuffix": "tapioni.com" },
              { "hostSuffix": "tsyndicate.com" }
            ],
            "navigationBlock": [
              { "hostSuffix": "creative.19sex.live" },
              { "hostSuffix": "realsrv.com" },
              { "hostSuffix": "labadena.com" }
            ],
            "domRemove": [
              { "selector": "iframe[src*=\"creative.19sex.live\"]" },
              { "selector": "iframe[src*=\"realsrv.com\"]" },
              { "selector": "iframe[src*=\"labadena.com\"]" },
              { "selector": "script[src*=\"creative.19sex.live\"]" }
            ]
          },
            "avple.tv": {
              "allowRequests": [
                { "hostSuffix": "cdnedge.live", "pathPrefix": "/file/avple-asserts/hls/" }
              ],
              "requestBlock": [
                { "hostSuffix": "whitetrafsa.com" },
                { "hostSuffix": "mavrtracktor.com" },
                { "hostSuffix": "doppio.com" },
                { "hostSuffix": "tsyndicate.com" }
            ],
            "navigationBlock": [
              { "hostSuffix": "whitetrafsa.com" },
              { "hostSuffix": "mavrtracktor.com" },
              { "hostSuffix": "tsyndicate.com" }
            ],
              "domRemove": [
                { "selector": "iframe[src*=\"whitetrafsa.com\"]" },
                { "selector": "iframe[src*=\"mavrtracktor.com\"]" }
              ]
            },
            "rouva8.xyz": {
              "requestBlock": [
                { "hostSuffix": "rouva8.xyz", "pathPrefix": "/api/hop/" },
                { "hostSuffix": "magsrv.com" },
                { "hostSuffix": "tsyndicate.com" }
              ],
              "navigationBlock": [
                { "hostSuffix": "rouva8.xyz", "pathPrefix": "/api/hop/" }
              ],
              "domRemove": [
                { "selector": "div:has(> a[href*=\"/api/hop/\"])" },
                { "selector": "a[href*=\"/api/hop/\"]" }
              ]
            }
        }
        """.trimIndent()
        
        // 預設規則（首次安裝時使用）
        // 這些內建規則會在雲端規則更新時一併保留，避免已知廣告重新出現。
        private val BUILTIN_COMMON_BLOCK_OVERRIDES = listOf(
            "c0.jdbstatic.com/ads/",
            "udzpel.com",
            "i.cyrady.com",
            "cdn.pornfhd.com/files/banner_300x100.html",
            "creative.whitetrafsa.com/widgets",
            "go.javhdporn.live",
            "javhd-trk.com",
            "creative.19sex.live",
            "realsrv.com",
            "labadena.com",
            "go.mavrtracktor.com",
            "magsrv.com",
            "tsyndicate.com"
        )

        private val DEFAULT_RULES = """
        {
          "version": "3.1.0",
          "lastUpdate": "2026-08-18T00:00:00Z",
          "domains": {
            "missav": "missav.ws",
            "jable": "jable.tv",
            "rou_video": "rouva8.xyz",
            "avjoy": "avjoy.me",
            "7mmtv": "7mmtv.sx",
            "avple": "avple.tv",
            "whos": "whos.tv"
          },
          "rules": {
            "commonBlock": [
              "creative.myavlive.com",
              "silent-basis.pro",
              "ptelastaxo.com",
              "magsrv.com",
              "afcdn.net",
              "siscprts.com",
              "exoclick.com",
              "go.mnaspm.com",
              "onclckbn.net",
              "smartpop",
              "tsyndicate.com",
              "ad-provider.js",
              "ra12.xyz",
              "rdz1.xyz",
              "uug27.com",
              "fluxtrck.site",
              "fuu78.com",
              "zlinkr.com",
              "mnaspm.com",
              "shukriya90.com",
              "shopee",
              "shp.ee",
              "lazada",
              "c0.jdbstatic.com/ads/",
              "udzpel.com",
              "i.cyrady.com",
              "cdn.pornfhd.com/files/banner_300x100.html",
              "creative.whitetrafsa.com/widgets",
              "go.javhdporn.live",
              "javhd-trk.com",
              "creative.19sex.live",
              "realsrv.com",
              "labadena.com",
              "go.mavrtracktor.com"
            ],
            "networkBlock": [],
            "linkBlock": [],
            "iframeBlock": [],
            "redirectBlock": []
          }
        }
        """.trimIndent()
    }
    
    init {
        // 如果是首次使用，載入預設規則
        if (!prefs.contains(KEY_RULES_JSON)) {
            updateRulesFromJson(DEFAULT_RULES)
        }
    }
    
    // 獲取網路層攔截列表
    fun getNetworkBlockList(): List<String> {
        return getRulesList(RuleType.NETWORK_BLOCK)
    }
    
    // 獲取超連結遮蔽列表
    fun getLinkBlockList(): List<String> {
        return getRulesList(RuleType.LINK_BLOCK)
    }
    
    // 獲取 iframe 遮蔽列表
    fun getIframeBlockList(): List<String> {
        return getRulesList(RuleType.IFRAME_BLOCK)
    }
    
    // 獲取重定向阻擋列表
    fun getRedirectBlockList(): List<String> {
        return getRulesList(RuleType.REDIRECT_BLOCK)
    }
    
    // 獲取通用遮蔽列表（僅 commonBlock，不合併）
    fun getCommonBlockList(): List<String> {
        return try {
            val json = prefs.getString(KEY_RULES_JSON, DEFAULT_RULES) ?: DEFAULT_RULES
            val jsonObject = JSONObject(json)
            val rulesObject = jsonObject.getJSONObject("rules")
            
            val array = rulesObject.getJSONArray("commonBlock")
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            BUILTIN_COMMON_BLOCK_OVERRIDES.forEach { rule ->
                if (!list.contains(rule)) list.add(rule)
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 讀取網站專屬規則。網站 key 使用主網域，例如 avtoday.io，並自動涵蓋其子網域。
     * 未提供 siteRules 的舊版 JSON 會回傳空 Map，維持向下相容。
     */
    fun getSiteRules(): Map<String, SiteAdRuleSet> {
        return try {
            val json = prefs.getString(KEY_RULES_JSON, DEFAULT_RULES) ?: DEFAULT_RULES
            val rulesObject = JSONObject(json).getJSONObject("rules")
            val sitesObject = rulesObject.optJSONObject("siteRules") ?: JSONObject(DEFAULT_SITE_RULES)
            val result = linkedMapOf<String, SiteAdRuleSet>()
            sitesObject.keys().forEach { rawHost ->
                val host = rawHost.trim().trimStart('.').lowercase()
                val site = sitesObject.optJSONObject(rawHost) ?: return@forEach
                if (host.isEmpty()) return@forEach
                result[host] = SiteAdRuleSet(
                    allowRequests = parseSiteUrlRules(site.optJSONArray("allowRequests")),
                    requestBlock = parseSiteUrlRules(site.optJSONArray("requestBlock")),
                    navigationBlock = parseSiteUrlRules(site.optJSONArray("navigationBlock")),
                    domRemove = parseSiteDomRules(site.optJSONArray("domRemove"))
                )
            }
            // 舊版已儲存在裝置上的規則可能沒有新站點；保留雲端規則的同時，
            // 將內建的新站點規則補進來，讓更新 APK 後立即生效。
            val builtInSites = JSONObject(DEFAULT_SITE_RULES)
            builtInSites.keys().forEach { rawHost ->
                val host = rawHost.trim().trimStart('.').lowercase()
                if (host.isEmpty() || result.containsKey(host)) return@forEach
                val site = builtInSites.optJSONObject(rawHost) ?: return@forEach
                result[host] = SiteAdRuleSet(
                    allowRequests = parseSiteUrlRules(site.optJSONArray("allowRequests")),
                    requestBlock = parseSiteUrlRules(site.optJSONArray("requestBlock")),
                    navigationBlock = parseSiteUrlRules(site.optJSONArray("navigationBlock")),
                    domRemove = parseSiteDomRules(site.optJSONArray("domRemove"))
                )
            }
            result
        } catch (e: Exception) {
            android.util.Log.e("AdBlock", "siteRules parse failed: ${e.message}")
            emptyMap()
        }
    }

    private fun parseSiteUrlRules(array: JSONArray?): List<SiteUrlRule> {
        if (array == null) return emptyList()
        val result = mutableListOf<SiteUrlRule>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            fun stringOrNull(key: String): String? =
                item.optString(key, "").trim().takeIf { it.isNotEmpty() }
            val mainFrame = if (item.has("mainFrame") && !item.isNull("mainFrame")) {
                item.optBoolean("mainFrame")
            } else null
            val rule = SiteUrlRule(
                hostEquals = stringOrNull("hostEquals"),
                hostSuffix = stringOrNull("hostSuffix"),
                pathEquals = stringOrNull("pathEquals"),
                pathPrefix = stringOrNull("pathPrefix"),
                urlContains = stringOrNull("urlContains"),
                mainFrame = mainFrame
            )
            val hasMatcher = rule.hostEquals != null || rule.hostSuffix != null ||
                rule.pathEquals != null || rule.pathPrefix != null ||
                rule.urlContains != null || rule.mainFrame != null
            if (hasMatcher) result.add(rule)
        }
        return result
    }

    private fun parseSiteDomRules(array: JSONArray?): List<SiteDomRemoveRule> {
        if (array == null) return emptyList()
        val result = mutableListOf<SiteDomRemoveRule>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val selector = item.optString("selector", "").trim()
            if (selector.isEmpty()) continue
            val closest = item.optString("closest", "").trim().takeIf { it.isNotEmpty() }
            result.add(SiteDomRemoveRule(selector, closest))
        }
        return result
    }
    
    // 內部方法：根據類型獲取規則列表（會合併 commonBlock）
    private fun getRulesList(type: RuleType): List<String> {
        return try {
            val json = prefs.getString(KEY_RULES_JSON, DEFAULT_RULES) ?: DEFAULT_RULES
            val jsonObject = JSONObject(json)
            val rulesObject = jsonObject.getJSONObject("rules")
            
            val result = mutableListOf<String>()
            
            // 1. 先添加 commonBlock 中的域名（適用於所有類型）
            try {
                val commonArray = rulesObject.getJSONArray("commonBlock")
                for (i in 0 until commonArray.length()) {
                    result.add(commonArray.getString(i))
                }
            } catch (e: Exception) {
                // commonBlock 不存在也沒關係，繼續
            }
            
            // 2. 再添加特定類型的域名
            val key = when (type) {
                RuleType.NETWORK_BLOCK -> "networkBlock"
                RuleType.LINK_BLOCK -> "linkBlock"
                RuleType.IFRAME_BLOCK -> "iframeBlock"
                RuleType.REDIRECT_BLOCK -> "redirectBlock"
            }
            
            try {
                val array = rulesObject.getJSONArray(key)
                for (i in 0 until array.length()) {
                    val domain = array.getString(i)
                    if (!result.contains(domain)) {  // 避免重複
                        result.add(domain)
                    }
                }
            } catch (e: Exception) {
                // 特定列表不存在也沒關係
            }
            
            result
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // 從 JSON 更新規則
    fun updateRulesFromJson(json: String): Boolean {
        return try {
            // 驗證 JSON 格式
            val jsonObject = JSONObject(json)
            jsonObject.getString("version")
            val rulesObject = jsonObject.getJSONObject("rules")

            // 保留 App 內建的精確廣告規則，即使雲端 JSON 尚未同步該項目。
            val commonArray = rulesObject.optJSONArray("commonBlock") ?: JSONArray()
            BUILTIN_COMMON_BLOCK_OVERRIDES.forEach { rule ->
                var exists = false
                for (i in 0 until commonArray.length()) {
                    if (commonArray.optString(i) == rule) {
                        exists = true
                        break
                    }
                }
                if (!exists) commonArray.put(rule)
            }
            rulesObject.put("commonBlock", commonArray)
            jsonObject.put("rules", rulesObject)
            
            // 至少要有 commonBlock 或其中一個特定列表
            val hasCommonBlock = rulesObject.has("commonBlock")
            val hasNetworkBlock = rulesObject.has("networkBlock")
            val hasLinkBlock = rulesObject.has("linkBlock")
            val hasIframeBlock = rulesObject.has("iframeBlock")
            val hasRedirectBlock = rulesObject.has("redirectBlock")
            val hasSiteRules = rulesObject.has("siteRules")
            
            if (!hasCommonBlock && !hasNetworkBlock && !hasLinkBlock && !hasIframeBlock &&
                !hasRedirectBlock && !hasSiteRules) {
                return false  // 完全沒有規則
            }
            
            // 保存
            prefs.edit().apply {
                putString(KEY_RULES_JSON, json)
                putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                apply()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    // 從雲端更新規則
    fun updateRulesFromCloud(url: String, callback: (success: Boolean, message: String) -> Unit) {
        thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                if (connection.responseCode == 200) {
                    val json = connection.inputStream.bufferedReader().readText()
                    val success = updateRulesFromJson(json)
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (success) {
                            callback(true, "規則更新成功")
                        } else {
                            callback(false, "JSON 格式錯誤")
                        }
                    }
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        callback(false, "網路錯誤: ${connection.responseCode}")
                    }
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback(false, "更新失敗: ${e.message}")
                }
            }
        }
    }
    
    // 新增規則
    fun addRule(type: RuleType, domain: String): Boolean {
        return try {
            val json = prefs.getString(KEY_RULES_JSON, DEFAULT_RULES) ?: DEFAULT_RULES
            val jsonObject = JSONObject(json)
            val rulesObject = jsonObject.getJSONObject("rules")
            
            val key = when (type) {
                RuleType.NETWORK_BLOCK -> "networkBlock"
                RuleType.LINK_BLOCK -> "linkBlock"
                RuleType.IFRAME_BLOCK -> "iframeBlock"
                RuleType.REDIRECT_BLOCK -> "redirectBlock"
            }
            
            val array = rulesObject.getJSONArray(key)
            
            // 檢查是否已存在
            for (i in 0 until array.length()) {
                if (array.getString(i) == domain) {
                    return false // 已存在
                }
            }
            
            // 新增
            array.put(domain)
            rulesObject.put(key, array)
            jsonObject.put("rules", rulesObject)
            
            updateRulesFromJson(jsonObject.toString())
        } catch (e: Exception) {
            false
        }
    }
    
    // 移除規則
    fun removeRule(type: RuleType, domain: String): Boolean {
        return try {
            val json = prefs.getString(KEY_RULES_JSON, DEFAULT_RULES) ?: DEFAULT_RULES
            val jsonObject = JSONObject(json)
            val rulesObject = jsonObject.getJSONObject("rules")
            
            val key = when (type) {
                RuleType.NETWORK_BLOCK -> "networkBlock"
                RuleType.LINK_BLOCK -> "linkBlock"
                RuleType.IFRAME_BLOCK -> "iframeBlock"
                RuleType.REDIRECT_BLOCK -> "redirectBlock"
            }
            
            val array = rulesObject.getJSONArray(key)
            val newArray = JSONArray()
            
            // 複製除了要刪除的項目
            for (i in 0 until array.length()) {
                val item = array.getString(i)
                if (item != domain) {
                    newArray.put(item)
                }
            }
            
            rulesObject.put(key, newArray)
            jsonObject.put("rules", rulesObject)
            
            updateRulesFromJson(jsonObject.toString())
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 讀取 domains 設定區塊（用於動態網域替換）
     * 回傳格式：Map<"missav" -> "missav.ws", ...>
     */
    fun getDomains(): Map<String, String> {
        return try {
            val json = prefs.getString(KEY_RULES_JSON, DEFAULT_RULES) ?: DEFAULT_RULES
            val jsonObject = JSONObject(json)
            if (!jsonObject.has("domains")) return emptyMap()
            val domainsObject = jsonObject.getJSONObject("domains")
            val map = mutableMapOf<String, String>()
            domainsObject.keys().forEach { key ->
                map[key] = domainsObject.getString(key)
            }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // 導出規則為 JSON
    fun exportToJson(): String {
        return prefs.getString(KEY_RULES_JSON, DEFAULT_RULES) ?: DEFAULT_RULES
    }
    
    // 導入規則
    fun importFromJson(json: String): Boolean {
        return updateRulesFromJson(json)
    }
    
    // 獲取規則統計
    fun getRulesStats(): Map<String, Int> {
        val commonCount = getCommonBlockList().size
        val networkCount = getRulesListOnly(RuleType.NETWORK_BLOCK).size
        val linkCount = getRulesListOnly(RuleType.LINK_BLOCK).size
        val iframeCount = getRulesListOnly(RuleType.IFRAME_BLOCK).size
        val redirectCount = getRulesListOnly(RuleType.REDIRECT_BLOCK).size
        
        return mapOf(
            "commonBlock" to commonCount,
            "networkBlock" to networkCount,
            "linkBlock" to linkCount,
            "iframeBlock" to iframeCount,
            "redirectBlock" to redirectCount,
            "total" to (commonCount + networkCount + linkCount + iframeCount + redirectCount)
        )
    }
    
    // 僅獲取特定類型規則（不包含 commonBlock）
    private fun getRulesListOnly(type: RuleType): List<String> {
        return try {
            val json = prefs.getString(KEY_RULES_JSON, DEFAULT_RULES) ?: DEFAULT_RULES
            val jsonObject = JSONObject(json)
            val rulesObject = jsonObject.getJSONObject("rules")
            
            val key = when (type) {
                RuleType.NETWORK_BLOCK -> "networkBlock"
                RuleType.LINK_BLOCK -> "linkBlock"
                RuleType.IFRAME_BLOCK -> "iframeBlock"
                RuleType.REDIRECT_BLOCK -> "redirectBlock"
            }
            
            val array = rulesObject.getJSONArray(key)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // 獲取版本和更新時間
    fun getVersion(): String {
        return try {
            val json = prefs.getString(KEY_RULES_JSON, DEFAULT_RULES) ?: DEFAULT_RULES
            JSONObject(json).getString("version")
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    fun getLastUpdateTime(): Long {
        return prefs.getLong(KEY_LAST_UPDATE, 0L)
    }
    
    // 雲端 URL 管理
    var cloudUrl: String
        get() = prefs.getString(KEY_CLOUD_URL, DEFAULT_CLOUD_URL) ?: DEFAULT_CLOUD_URL
        set(value) = prefs.edit().putString(KEY_CLOUD_URL, value).apply()
}
