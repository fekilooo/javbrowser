package com.example.javbrowser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btnPlay: Button
    private lateinit var btnHome: Button
    private lateinit var btnAddFavorite: Button
    private lateinit var btnViewFavorites: Button
    private lateinit var btnSettings: Button
    private lateinit var favoritesManager: FavoritesManager
    private lateinit var privacySettings: PrivacySettings
    private lateinit var biometricHelper: BiometricHelper
    private var currentVideoUrl: String? = null
    private var videoFoundToastShown = false
    private var isUnlocked = false
    private var isFreshStart = true
    private val REQUEST_CODE_FAVORITES = 1001
    private val REQUEST_CODE_LOCK = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and hide content in recent apps
        window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        favoritesManager = FavoritesManager(this)
        privacySettings = PrivacySettings(this)
        // biometricHelper = BiometricHelper(this) // Moved to LockActivity
        
        webView = findViewById(R.id.webView)
        btnPlay = findViewById(R.id.btn_play)
        btnHome = findViewById(R.id.btn_home)
        btnAddFavorite = findViewById(R.id.btn_add_favorite)
        btnViewFavorites = findViewById(R.id.btn_view_favorites)
        btnSettings = findViewById(R.id.btn_settings)

        initializeApp()
    }
    
    private fun initializeApp() {
        setupWebView()
        setupPlayButton()
        setupHomeButton()
        setupFavoritesButtons()
        setupSettingsButton()

        loadLandingPage()
    }

    // ... (rest of the file)

    override fun onResume() {
        super.onResume()
        // Check if lock is needed when returning from background or fresh start
        if (privacySettings.isLockEnabled && !isUnlocked) {
            if (isFreshStart || privacySettings.shouldLock()) {
                val intent = Intent(this, LockActivity::class.java)
                startActivityForResult(intent, REQUEST_CODE_LOCK)
            }
        }
        isFreshStart = false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_FAVORITES && resultCode == RESULT_OK) {
            val url = data?.getStringExtra("url")
            if (url != null) {
                webView.loadUrl(url)
            }
        } else if (requestCode == REQUEST_CODE_LOCK) {
            if (resultCode == RESULT_OK) {
                isUnlocked = true
                privacySettings.updateUnlockTime()
            } else {
                // Lock failed or cancelled, finish app
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // App is going to background, reset unlock state if lock is enabled
        if (privacySettings.isLockEnabled) {
            isUnlocked = false
        }
    }

    // ... (rest of the file)



    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                val lowerUrl = url.lowercase()

                // Block Shopee and Lazada redirects
                if (lowerUrl.contains("shopee") || lowerUrl.contains("shp.ee") || lowerUrl.contains("lazada")) {
                    return true
                }
                
                // Handle APK download
                if (url.contains(".apk") || url.contains("down_ra")) {
                    downloadAndInstallApk(url)
                    return true
                }
                
                // Allow navigation to target URLs
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url.toString()
                val lowerUrl = url.lowercase()
                
                /* AD BLOCKING DISABLED
                if (isAd(lowerUrl)) {
                    // Block ad
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                }
                */

                // Block specific ad script source
                if (lowerUrl.contains("creative.myavlive.com") || 
                    lowerUrl.contains("silent-basis.pro") || 
                    lowerUrl.contains("ptelastaxo.com") ||
                    lowerUrl.contains("magsrv.com") ||
                    lowerUrl.contains("afcdn.net") ||
                    lowerUrl.contains("siscprts.com") ||
                    lowerUrl.contains("exoclick.com") ||
                    lowerUrl.contains("go.mnaspm.com") ||
                    lowerUrl.contains("smartpop") ||
                    lowerUrl.contains("tsyndicate.com") ||
                    lowerUrl.contains("ad-provider.js") ||
                    lowerUrl.contains("shopee") || 
                    lowerUrl.contains("shp.ee") || 
                    lowerUrl.contains("lazada")) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                }
                
                // Video Sniffing: Check if request is for an m3u8 playlist
                if (url.contains(".m3u8") && !url.contains("minisite")) {
                    // Found a video URL!
                    // Run on UI thread to update UI
                    view?.post {
                         if (currentVideoUrl != url) {
                             currentVideoUrl = url
                             btnPlay.visibility = View.VISIBLE
                             // if (!videoFoundToastShown) {
                             //     Toast.makeText(this@MainActivity, R.string.video_found, Toast.LENGTH_SHORT).show()
                             //     videoFoundToastShown = true
                             // }
                         }
                    }
                }
                
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                btnPlay.visibility = View.GONE
                currentVideoUrl = null
                videoFoundToastShown = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Do NOT reset btnPlay or currentVideoUrl here, as video might have been found during load
                
                // Inject JS to remove specific ad elements
                val removeAdsJs = """
                    (function() {
                        function removeAds() {
                            // Remove iframes with ID starting with 'container-'
                            var iframes = document.querySelectorAll('iframe[id^="container-"]');
                            iframes.forEach(function(iframe) {
                                iframe.remove();
                            });
                            
                            // Remove elements with high z-index and fixed position (common for overlays)
                            var allElements = document.getElementsByTagName('*');
                            for (var i = 0; i < allElements.length; i++) {
                                var el = allElements[i];
                                var style = window.getComputedStyle(el);
                                if (style.position === 'fixed' && style.zIndex > 2000000000) {
                                    el.style.display = 'none';
                                    el.remove();
                                }
                            }

                            // Rou.Video specific ad removal - Enhanced
                            var rmpAds = document.querySelectorAll('.rmp-ad-container, .rootContent--OjJEv');
                            rmpAds.forEach(function(ad) { ad.remove(); });
                            
                            // PRIORITY: Remove ALL tscprts.com related elements (all sites)
                            var tscprtsElements = document.querySelectorAll('a[href*="tscprts.com"], a[href*="go.tscprts.com"]');
                            tscprtsElements.forEach(function(link) {
                                // Remove up to 3 levels of parent divs to ensure complete removal
                                var parent = link.parentElement;
                                for (var i = 0; i < 3 && parent; i++) {
                                    var nextParent = parent.parentElement;
                                    parent.remove();
                                    parent = nextParent;
                                }
                            });
                            
                            // Remove bottom-right floating ads by class patterns
                            var bottomRightAds = document.querySelectorAll('[class*="bottomRight"], [class*="slideAnimation"], [class*="root--"]');
                            bottomRightAds.forEach(function(ad) {
                                // Additional check: if it contains tscprts or doppiocdn links
                                if (ad.innerHTML && (ad.innerHTML.includes('tscprts.com') || ad.innerHTML.includes('doppiocdn.com'))) {
                                    ad.remove();
                                }
                            });
                            
                            // ENHANCED: Remove close-button ads and their parent containers (up to 2 levels)
                            // BUT exclude video player controls (vjs-*)
                            var closeButtons = document.querySelectorAll('[class*="close-button"]');
                            closeButtons.forEach(function(btn) {
                                // Skip if it's a video player control button
                                if (btn.className.includes('vjs-') || btn.className.includes('video-js')) {
                                    return; // Skip video player buttons
                                }
                                
                                // First, try to auto-click the button
                                try { btn.click(); } catch(e) {}
                                
                                // Remove up to 2 levels of parent to get the entire ad container
                                var parent = btn.parentElement;
                                if (parent) {
                                    var grandParent = parent.parentElement;
                                    if (grandParent) {
                                        grandParent.remove();
                                    } else {
                                        parent.remove();
                                    }
                                } else {
                                    btn.remove();
                                }
                            });
                            
                            // Remove dialog overlays by ID pattern or role
                            var dialogs = document.querySelectorAll('div[role="dialog"], div[id^="radix-"]');
                            dialogs.forEach(function(dialog) { dialog.remove(); });
                            
                            // Remove specific ad links/images - Enhanced with Safeguard
                            function isSafeToRemove(element) {
                                if (!element) return false;
                                if (element.id === 'player') return false;
                                if (element.classList.contains('video-js')) return false;
                                if (element.classList.contains('vjs-tech')) return false;
                                if (element.querySelector && (element.querySelector('#player') || element.querySelector('.video-js'))) return false;
                                return true;
                            }

                            var adLinks = document.querySelectorAll('a[href*="ra12.xyz"], a[href*="tscprts.com"], a[href*="doppiocdn.com"], img[src*="doppiocdn.com"]');
                            adLinks.forEach(function(link) { 
                                var parent = link.closest('div');
                                if (parent && isSafeToRemove(parent)) {
                                    parent.remove();
                                } else {
                                    link.remove(); 
                                }
                            });

                            // Generic removal for bottom floating ads (all sites now, not just rou.video)
                            var allDivs = document.getElementsByTagName('div');
                            for (var i = 0; i < allDivs.length; i++) {
                                var el = allDivs[i];
                                var style = window.getComputedStyle(el);
                                // Check for fixed position at bottom or bottom-right
                                if (style.position === 'fixed' && (style.bottom === '0px' || parseInt(style.bottom) < 100)) {
                                    // Check if contains ad indicators
                                    if (el.innerText.includes('Close') || el.innerHTML.includes('ra12.xyz') || 
                                        el.innerHTML.includes('tscprts') || el.innerHTML.includes('go.tscprts') ||
                                        el.innerHTML.includes('blob:') || style.zIndex > 100) {
                                        el.style.display = 'none';
                                        el.remove();
                                    }
                                }
                            }


                            // Auto-click "Close ad" buttons (but skip video player controls)
                            var buttons = document.querySelectorAll('button, div[role="button"], a');
                            buttons.forEach(function(btn) {
                                // Skip video player controls
                                if (btn.className.includes('vjs-') || btn.className.includes('video-js')) {
                                    return;
                                }
                                
                                var text = btn.innerText || "";
                                if (text.toLowerCase().includes("close ad") || (text.toLowerCase() === "close" && !btn.closest('.video-js')) || text.includes("×")) {
                                    // Check if it looks like an ad close button (heuristic)
                                    if (btn.className.includes("close") || btn.className.includes("dismiss") || 
                                        (btn.style.position === 'absolute' && btn.style.top)) {
                                        try { btn.click(); } catch(e) {}
                                        btn.remove(); // Remove it after clicking just in case
                                    }
                                }
                            });
                        }
                        
                        // Run immediately and periodically
                        removeAds();
                        setInterval(removeAds, 1000);
                    })();
                """.trimIndent()
                // view?.evaluateJavascript(removeAdsJs, null) // DISABLED FOR TESTING

                // New MISSAV Ad Blocking Logic
                if (url?.contains("missav") == true || url?.contains("jable") == true || url?.contains("rou.video") == true) {
                    val missavAdBlockJs = """
                        (function() {
                            'use strict';

                            // 1. 攔截彈窗與惡意跳轉邏輯
                            var websites = ["missav.com/pop", "tsyndicate.com/api", "missav.ws/pop"];
                            var url = window.location.href;
                            for (var i = 0; i < websites.length; i++) {
                                // 簡單的正則匹配
                                if (url.indexOf(websites[i]) !== -1) {
                                    // 在WebView中，window.close() 可能無效，通常需要透過 about:blank 停止加載
                                    window.location.href = "about:blank";
                                    return; // 停止後續執行
                                }
                            }

                            // 2. 移除廣告 DOM 元素的函數
                            function cleanAds() {
                                // 移除特定的廣告區塊 (class 僅為 mx-auto 的元素)
                                try {
                                    const mxauto = document.querySelectorAll('.mx-auto:not([class*=" "])');
                                    mxauto.forEach(node => node.remove());
                                } catch (e) {}

                                // 移除特定的 root + bottomRight 廣告區塊 (動態識別)
                                try {
                                    // 1. 注入 CSS 強制隱藏
                                    var style = document.createElement('style');
                                    style.innerHTML = `
                                        div[class*="root"][class*="bottomRight"],
                                        div[role="dialog"]:not([data-slot="sheet-content"]),
                                        div[id^="radix-"]:not([data-slot="sheet-content"]),
                                        div[id^="__clb-spot_"],
                                        div[id^="ts_ad_"],
                                        div[id^="exo-native-widget"],
                                        .exo-native-widget,
                                        div[data-banner-id],
                                        .rmp-ad-container,
                                        script[src*="magsrv.com"],
                                        ins[data-zoneid],
                                        ins {
                                            display: none !important;
                                        }
                                    `;
                                    document.head.appendChild(style);

                                    // 2. 使用 MutationObserver 監聽並移除
                                    var observer = new MutationObserver(function(mutations) {
                                        // Safeguard function
                                        function isSafeToRemove(element) {
                                            if (!element) return false;
                                            if (element.id === 'player') return false;
                                            if (element.classList.contains('video-js')) return false;
                                            if (element.classList.contains('vjs-tech')) return false;
                                            if (element.querySelector && (element.querySelector('#player') || element.querySelector('.video-js'))) return false;
                                            return true;
                                        }

                                        mutations.forEach(function(mutation) {
                                            mutation.addedNodes.forEach(function(node) {
                                                if (node.nodeType === 1) { // Element
                                                    // Check if node matches generic ad selectors
                                                    if (node.matches && (
                                                        node.matches('div[class*="root"][class*="bottomRight"]') ||
                                                        (node.matches('div[role="dialog"]') && node.getAttribute('data-slot') !== 'sheet-content') ||
                                                        (node.matches('div[id^="radix-"]') && node.getAttribute('data-slot') !== 'sheet-content') ||
                                                        node.matches('div[id^="__clb-spot_"]') ||
                                                        node.matches('div[id^="ts_ad_"]') ||
                                                        node.matches('div[data-banner-id]') ||
                                                        node.matches('.rmp-ad-container') ||
                                                        node.matches('ins')
                                                    )) {
                                                        if (isSafeToRemove(node)) {
                                                            node.remove();
                                                        }
                                                    }
                                                    
                                                    // Check for Rou.Video specific cards (已停用以避免誤刪播放器)
                                                    // if (node.matches && node.matches('div[data-slot="card"]')) {
                                                    //     if (isSafeToRemove(node) && (node.innerText.includes('通告') || node.innerHTML.includes('ra12.xyz'))) {
                                                    //         node.remove();
                                                    //     }
                                                    // }

                                                    // Check for dynamic ad links
                                                    if (node.matches && (node.matches('a[href*="ra12.xyz"]') || node.matches('a[href*="rdz1.xyz"]'))) {
                                                        var container = node.closest('.grid') || node.closest('div');
                                                        if (container && isSafeToRemove(container)) {
                                                            container.remove();
                                                        } else {
                                                            node.remove();
                                                        }
                                                    }

                                                    // Check children of added node for ad links
                                                    var dynamicAdLinks = node.querySelectorAll('a[href*="ra12.xyz"], a[href*="rdz1.xyz"]');
                                                    dynamicAdLinks.forEach(link => {
                                                        var container = link.closest('.grid') || link.closest('div');
                                                        if (container && isSafeToRemove(container)) {
                                                            container.remove();
                                                        } else {
                                                            link.remove();
                                                        }
                                                    });
                                                    
                                                    // Also check children
                                                    var ads = node.querySelectorAll('div[class*="root"][class*="bottomRight"], div[role="dialog"]:not([data-slot="sheet-content"]), div[id^="radix-"]:not([data-slot="sheet-content"]), div[id^="__clb-spot_"], div[id^="ts_ad_"], div[data-banner-id], .rmp-ad-container, ins');
                                                    ads.forEach(ad => {
                                                        if (isSafeToRemove(ad)) {
                                                            ad.remove();
                                                        }
                                                    });
                                                }
                                            });
                                        });
                                    });
                                    observer.observe(document.body, { childList: true, subtree: true });

                                    // 3. 初始移除 (播放器可能還沒載入，避免誤刪)
                                    function cleanAdsInitial() {
                                        // Safeguard
                                        function isSafeToRemove(element) {
                                            if (!element) return false;
                                            if (element.id === 'player') return false;
                                            if (element.classList.contains('video-js')) return false;
                                            if (element.classList.contains('vjs-tech')) return false;
                                            if (element.querySelector && (element.querySelector('#player') || element.querySelector('.video-js'))) return false;
                                            return true;
                                        }

                                        // Generic selectors (不包括 card，因為播放器可能還沒載入)
                                        const selectors = [
                                            'div[class*="root"][class*="bottomRight"]',
                                            'div[role="dialog"]:not([data-slot="sheet-content"])',
                                            'div[id^="radix-"]:not([data-slot="sheet-content"])',
                                            'div[id^="__clb-spot_"]',
                                            'div[id^="ts_ad_"]',
                                            'div[id^="exo-native-widget"]',
                                            '.exo-native-widget',
                                            'div[data-banner-id]',
                                            '.rmp-ad-container',
                                            'ins[data-zoneid]',
                                            'ins'
                                        ];
                                        
                                        selectors.forEach(selector => {
                                            document.querySelectorAll(selector).forEach(node => {
                                                if (isSafeToRemove(node)) {
                                                    node.style.display = 'none';
                                                    node.remove();
                                                }
                                            });
                                        });
                                        
                                        // Remove magsrv.com ad scripts (jable.tv)
                                        document.querySelectorAll('script[src*="magsrv.com"]').forEach(script => {
                                            script.remove();
                                        });
                                        
                                        // Generic ad links and their containers
                                        document.querySelectorAll('a[href*="ra12.xyz"], a[href*="rdz1.xyz"]').forEach(link => {
                                            var container = link.closest('.grid') || link.closest('div');
                                            if (container && isSafeToRemove(container)) {
                                                container.remove();
                                            } else {
                                                link.remove();
                                            }
                                        });
                                    }
                                    
                                    
                                    cleanAdsInitial();
                                } catch (e) {}

                                // 嘗試點擊各種類型的關閉按鈕
                                // 註：這些 Class 名稱可能是混淆過的，網站更新後可能失效
                                const closeSelectors = [
                                    ".close-button--wsOv0",
                                    ".absolute.top-1.right-1.p-0.5.bg-black.rounded-lg.opacity-70"
                                ];

                                closeSelectors.forEach(selector => {
                                    const btns = document.querySelectorAll(selector);
                                    btns.forEach(btn => btn.click());
                                });
                            }

                            // 執行邏輯
                            cleanAds();

                            // 延遲執行 (應對動態加載的廣告)
                            setTimeout(cleanAds, 1000);
                            setTimeout(cleanAds, 2500);
                            setTimeout(cleanAds, 5000);

                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(missavAdBlockJs, null)
                }
                
                checkForVideo()
                updateFavoriteIcon()
            }
        }
    }

    private fun isAd(url: String): Boolean {
        val adKeywords = listOf(
            // General ad networks
            "googleads", "doubleclick", "adservice", "googlesyndication",
            "adnxs", "advertising", "adsystem", "adtech", "adform",
            
            // Adult ad networks (common on these sites)
            "popunder", "juicyads", "exoclick", "trafficjunky", 
            "plugrush", "adsterra", "popcash", "propeller", "popads",
            "tsyndicate", "realsrv", "hilltopads", "adcash",
            
            // Tracking & Analytics that might show popups
            "pubmatic", "outbrain", "taboola", "smartadserver",
            "criteo", "bidvertiser", "vibrantmedia",
            
            // Keywords in URL paths
            "/ads/", "/ad/", "/banner/", "/popup/", "/popunder/",
            "banner", "sponsor", "tracking", "clicktrack",
            
            // Specific domains requested by user
            "myavlive.com", "snaptrckr.fun", "stripchat.com", 
            "adxadserv.com", "fluxtrck.site", "ra12.xyz"
        )
        
        // Check if URL contains any ad keyword
        return adKeywords.any { url.contains(it) }
    }

    private fun checkForVideo() {
        val url = webView.url ?: return
        
        // Only check on likely video pages to save resources
        // Jable: /videos/
        // MissAV: usually has UUID or just check all pages on missav domain
        
        // Special handling for rou.video - monitor video src continuously
        if (url.contains("rou.video")) {
            // Inject JS to monitor video element for src changes
            val monitorJs = """
                (function() {
                    if (window.rouVideoMonitor) return; // Already monitoring
                    window.rouVideoMonitor = true;
                    
                    var checkInterval = setInterval(function() {
                        var video = document.querySelector('video');
                        if (video && video.src && video.src.startsWith('http')) {
                            Android.onVideoFound(video.src);
                            clearInterval(checkInterval);
                        }
                    }, 1000); // Check every second
                    
                    // Stop checking after 30 seconds
                    setTimeout(function() {
                        clearInterval(checkInterval);
                    }, 30000);
                })();
            """.trimIndent()
            
            // Add JS interface to receive callback
            webView.addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun onVideoFound(videoUrl: String) {
                    runOnUiThread {
                        currentVideoUrl = videoUrl
                        btnPlay.visibility = View.VISIBLE
                        // Toast.makeText(this@MainActivity, R.string.video_found, Toast.LENGTH_SHORT).show()
                    }
                }
            }, "Android")
            
            webView.evaluateJavascript(monitorJs, null)
        } else {
            // For other sites, parse HTML
            webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { html ->
                // html is a JSON string, e.g. "\u003Chtml>..."
                // We need to unescape it.
                val rawHtml = unescapeJsString(html)
                
                var extractedUrl: String? = null
                
                if (url.contains("jable.tv")) {
                    extractedUrl = VideoExtractor.extractJable(rawHtml)
                } else if (url.contains("missav")) {
                    extractedUrl = VideoExtractor.extractMissAV(rawHtml)
                }

                if (extractedUrl != null) {
                    currentVideoUrl = extractedUrl
                    btnPlay.visibility = View.VISIBLE
                    // Toast.makeText(this, R.string.video_found, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun unescapeJsString(jsString: String): String {
        // Remove surrounding quotes
        var s = jsString
        if (s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        try {
            return org.json.JSONTokener(jsString).nextValue().toString()
        } catch (e: Exception) {
            return s.replace("\\u003C", "<").replace("\\\"", "\"").replace("\\\\", "\\")
        }
    }

    private fun setupPlayButton() {
        btnPlay.setOnClickListener {
            currentVideoUrl?.let { url ->
                playVideo(url)
            }
        }
    }

    private fun setupFavoritesButtons() {
        btnAddFavorite.setOnClickListener {
            val url = webView.url
            if (url != null && url.startsWith("http")) {
                val title = webView.title ?: "Unknown Page"
                val favorites = favoritesManager.getFavorites()
                val isFavorite = favorites.any { it.url == url }
                
                if (isFavorite) {
                    favoritesManager.removeFavorite(url)
                    Toast.makeText(this, "已從收藏移除", Toast.LENGTH_SHORT).show()
                    btnAddFavorite.text = "♡"
                } else {
                    // Extract thumbnail URL from current page
                    webView.evaluateJavascript("""
                        (function() {
                            var thumbnail = '';
                            // Try video poster
                            var video = document.querySelector('video[poster]');
                            if (video && video.poster) {
                                thumbnail = video.poster;
                            }
                            // Try og:image
                            if (!thumbnail) {
                                var ogImage = document.querySelector('meta[property="og:image"]');
                                if (ogImage) thumbnail = ogImage.content;
                            }
                            // Try twitter:image
                            if (!thumbnail) {
                                var twitterImage = document.querySelector('meta[name="twitter:image"]');
                                if (twitterImage) thumbnail = twitterImage.content;
                            }
                            // Try first img
                            if (!thumbnail) {
                                var img = document.querySelector('img');
                                if (img) thumbnail = img.src;
                            }
                            return thumbnail;
                        })();
                    """.trimIndent()) { thumbnailUrl ->
                        val cleanedThumbnailUrl = thumbnailUrl?.trim('"')?.takeIf { it.isNotEmpty() && it != "null" }
                        favoritesManager.addFavorite(title, url, cleanedThumbnailUrl)
                        Toast.makeText(this, "已加入收藏", Toast.LENGTH_SHORT).show()
                        btnAddFavorite.text = "♥"
                    }
                }
            } else {
                Toast.makeText(this, "Cannot add this page", Toast.LENGTH_SHORT).show()
            }
        }

        btnViewFavorites.setOnClickListener {
            val intent = Intent(this, FavoritesActivity::class.java)
            startActivityForResult(intent, REQUEST_CODE_FAVORITES)
        }
    }
    
    private fun updateFavoriteIcon() {
        val url = webView.url
        if (url != null && url.startsWith("http")) {
            val favorites = favoritesManager.getFavorites()
            val isFavorite = favorites.any { it.url == url }
            btnAddFavorite.text = if (isFavorite) "♥" else "♡"
        } else {
            btnAddFavorite.text = "♡"
        }
    }



    private fun playVideo(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(Uri.parse(url), "video/*") // Or application/x-mpegURL
            // Try to find a handler
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Try specifically for m3u8 type if generic video fails, or just toast
                intent.setDataAndType(Uri.parse(url), "application/x-mpegURL")
                 if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.error_no_player, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
    
    private fun setupHomeButton() {
        btnHome.setOnClickListener {
            loadLandingPage()
        }
    }
    
    private fun loadLandingPage() {
        // Reload the landing page
        val landingHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { 
                        background-color: #121212; 
                        color: white; 
                        font-family: sans-serif; 
                        display: flex; 
                        flex-direction: column; 
                        align-items: center; 
                        justify-content: flex-start; 
                        min-height: 100vh;
                        margin: 0;
                        padding: 60px 20px 20px 20px;
                        box-sizing: border-box;
                    }
                    h1 {
                        margin-bottom: 20px;
                        margin-top: 0;
                    }
                    .search-container {
                        width: 100%;
                        max-width: 500px;
                        margin-bottom: 30px;
                    }
                    .search-box {
                        width: 100%;
                        padding: 15px;
                        font-size: 16px;
                        border: 2px solid #BB86FC;
                        border-radius: 8px;
                        background-color: #1E1E1E;
                        color: white;
                        box-sizing: border-box;
                    }
                    .search-box:focus {
                        outline: none;
                        border-color: #CF6FFF;
                    }
                    .search-results {
                        width: 100%;
                        max-width: 500px;
                        display: none;
                        margin-top: 10px;
                    }
                    .search-results.show {
                        display: block;
                    }
                    a { 
                        display: block; 
                        margin: 10px 0; 
                        padding: 15px 30px; 
                        background-color: #BB86FC; 
                        color: black; 
                        text-decoration: none; 
                        border-radius: 8px; 
                        font-size: 16px; 
                        font-weight: bold;
                        text-align: center;
                    }
                    a:hover {
                        background-color: #CF6FFF;
                    }
                    .divider {
                        width: 100%;
                        max-width: 500px;
                        text-align: center;
                        margin: 20px 0;
                        color: #888;
                    }
                </style>
            </head>
            <body>
                <h1>JAV Browser</h1>
                
                <div class="search-container">
                    <input type="text" id="searchInput" class="search-box" placeholder="輸入搜尋關鍵字..." />
                    <div id="searchResults" class="search-results">
                        <a href="#" id="searchMissAV">在 MissAV 搜尋</a>
                        <a href="#" id="searchJable">在 Jable.TV 搜尋</a>
                    </div>
                </div>
                
                <div class="divider">或直接前往</div>
                
                <a href="https://missav.ws/">Go to MissAV</a>
                <a href="https://jable.tv/">Go to Jable.TV</a>
                <a href="https://rou.video/home">Go to Rou.Video</a>
                
                <script>
                    const searchInput = document.getElementById('searchInput');
                    const searchResults = document.getElementById('searchResults');
                    const searchMissAV = document.getElementById('searchMissAV');
                    const searchJable = document.getElementById('searchJable');
                    
                    searchInput.addEventListener('input', function() {
                        const keyword = this.value.trim();
                        if (keyword.length > 0) {
                            searchResults.classList.add('show');
                            searchMissAV.textContent = '在 MissAV 搜尋: ' + keyword;
                            searchJable.textContent = '在 Jable.TV 搜尋: ' + keyword;
                            
                            // Update URLs
                            searchMissAV.href = 'https://missav.ws/search/' + encodeURIComponent(keyword);
                            searchJable.href = 'https://jable.tv/search/' + encodeURIComponent(keyword) + '/';
                        } else {
                            searchResults.classList.remove('show');
                        }
                    });
                    
                    searchInput.addEventListener('keypress', function(e) {
                        if (e.key === 'Enter' && this.value.trim().length > 0) {
                            // Default to MissAV on Enter
                            window.location.href = searchMissAV.href;
                        }
                    });
                </script>
                
                <div style="margin-top: 40px; padding: 20px;">
                    <a href="https://www.277sy.com/index.php/Rmiddle/down_ra/?appid=401&tgid=da0003500&type=1" 
                       style="display: block; padding: 20px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); 
                              border-radius: 12px; text-decoration: none; color: white; text-align: center; 
                              font-size: 18px; font-weight: bold; box-shadow: 0 4px 12px rgba(0,0,0,0.3);">
                        🐱 靈貓遊戲 - 超低折扣 | 海量福利 | 專屬特權<br>
                        <span style="font-size: 14px; opacity: 0.9;">點擊下載 APP</span>
                    </a>
                </div>
            </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, landingHtml, "text/html", "utf-8", null)
    }
    
    private fun downloadAndInstallApk(url: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setData(android.net.Uri.parse(url))
        startActivity(intent)
    }
    
    private fun setupSettingsButton() {
        btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
    


}
