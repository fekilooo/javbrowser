package com.example.javbrowser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class AdFilterRules(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "ad_filter_rules"
        private const val KEY_RULES_JSON = "rules_json"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_CLOUD_URL = "cloud_url"
        const val DEFAULT_CLOUD_URL = "https://raw.githubusercontent.com/fekilooo/javbrowser/refs/heads/main/ad-filter-rules.json"
        
        // 預設規則（首次安裝時使用）
        private val DEFAULT_RULES = """
        {
          "version": "2.1.0",
          "lastUpdate": "2025-11-28T13:30:00Z",
          "domains": {
            "missav": "missav.ws",
            "jable": "jable.tv",
            "rou_video": "rouva2.xyz"
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
              "lazada"
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
            list
        } catch (e: Exception) {
            emptyList()
        }
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
            
            // 至少要有 commonBlock 或其中一個特定列表
            val hasCommonBlock = rulesObject.has("commonBlock")
            val hasNetworkBlock = rulesObject.has("networkBlock")
            val hasLinkBlock = rulesObject.has("linkBlock")
            val hasIframeBlock = rulesObject.has("iframeBlock")
            val hasRedirectBlock = rulesObject.has("redirectBlock")
            
            if (!hasCommonBlock && !hasNetworkBlock && !hasLinkBlock && !hasIframeBlock && !hasRedirectBlock) {
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
