package com.aistudio.cinestream.xyzabc.extensions.anime4up

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class VideoExtractor(
    private val context: Context,
    private val userAgent: String = DEFAULT_USER_AGENT,
    // ✅ قللنا المهلة من 25 ثانية إلى 12 — يكفي للمواقع المستهدفة
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "VideoExtractor"

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        // ✅ 12 ثانية بدل 25
        const val DEFAULT_TIMEOUT_MS = 12_000L

        private val DIRECT_VIDEO_EXTENSIONS = listOf(
            ".m3u8", ".mp4", ".mpd", ".webm", ".mkv", ".ts"
        )

        // ✅ نطاقات معروفة توفر روابط مباشرة عبر XHR/JS وليس in <video src>
        private val JS_HEAVY_HOSTS = listOf(
            "voe.sx", "mp4upload.com", "streamruby.com", "rubyvidhub.com",
            "playmogo.com", "dsvplay.com", "uqload.vc", "uqload.is",
            "share4max.com", "videa.hu", "vkvideo.ru", "vk.com"
        )
    }

    interface ExtractionCallback {
        fun onDirectVideo(videoUrl: String, headers: Map<String, String>)
        fun onIframeFallback(iframeUrl: String)
        fun onFailure(reason: String)
        fun onProgress(message: String) {}
    }

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private var timeoutJob: Job? = null
    private val finished = AtomicBoolean(false)
    private val interceptedUrls = ConcurrentLinkedQueue<String>()
    private var originalUrl: String = ""
    private var currentCallback: ExtractionCallback? = null

    // ============================================================
    //  API
    // ============================================================

    @SuppressLint("SetJavaScriptEnabled")
    fun extract(url: String, callback: ExtractionCallback) {
        if (url.isBlank()) {
            callback.onFailure("الرابط فارغ")
            return
        }
        this.originalUrl = url
        this.currentCallback = callback
        this.finished.set(false)
        this.interceptedUrls.clear()

        mainHandler.post {
            try {
                val wv = createWebView()
                this.webView = wv
                setupTimeout()
                callback.onProgress("جاري تحميل: ${shortHost(url)}")
                wv.loadUrl(url, buildHeaders())
            } catch (t: Throwable) {
                Log.e(TAG, "فشل تهيئة WebView", t)
                finishWith { it.onFailure("فشل تهيئة المشغل: ${t.message}") }
            }
        }
    }

    fun destroy() {
        timeoutJob?.cancel()
        timeoutJob = null
        mainHandler.post {
            try {
                webView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    clearHistory()
                    removeAllViews()
                    destroy()
                }
            } catch (_: Throwable) {}
            webView = null
        }
        finished.set(true)
    }

    // ============================================================
    //  WebView
    // ============================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = false
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = userAgent
                useWideViewPort = true
                loadWithOverviewMode = true
                // ✅ ضروري لمواقع مثل voe.sx التي تستخدم CSP
                @Suppress("DEPRECATION")
                allowContentAccess = true
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                    val text = msg.message()
                    if (text.startsWith("[A4UP]")) {
                        Log.d(TAG, "JS: $text")
                        // بعض السكربتات المزروعة قد ترسل روابط عبر console
                        if (text.contains(".m3u8") || text.contains(".mp4")) {
                            val m = Regex("""https?://[^\s"']+\.(m3u8|mp4|mpd|webm)[^\s"']*""")
                                .find(text)
                            m?.value?.let { captured ->
                                if (!interceptedUrls.contains(captured)) {
                                    interceptedUrls.add(captured)
                                    Log.d(TAG, "التقطت من console: $captured")
                                    finishWith { it.onDirectVideo(captured, buildHeaders()) }
                                }
                            }
                        }
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {

                // ============================================================
                //  ✅ الطبقة 2: اعتراض كل الطلبات (يشمل XHR/fetch/video)
                // ============================================================
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (isDirectVideoUrl(reqUrl)) {
                        Log.d(TAG, "التقطت عبر الشبكة: $reqUrl")
                        interceptedUrls.add(reqUrl)
                        finishWith { it.onDirectVideo(reqUrl, buildHeaders()) }
                    }
                    return null
                }

                // ============================================================
                //  ✅ الطبقة 1: حقن Hooks قبل تشغيل سكربتات الصفحة
                // ============================================================
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "onPageStarted: $url")

                    if (url != null && isDirectVideoUrl(url)) {
                        finishWith { it.onDirectVideo(url, buildHeaders()) }
                        return
                    }

                    // حقن مبكر — يلتقط fetch/XHR قبل أن تنفذ سكربتات المشغل
                    view?.evaluateJavascript(EARLY_HOOK_SCRIPT) { /* ignore */ }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "onPageFinished: $url")
                    if (url != null && isDirectVideoUrl(url)) {
                        finishWith { it.onDirectVideo(url, buildHeaders()) }
                        return
                    }
                    // ✅ الطبقة 3: مسح متكرر بفاصل زمني متزايد
                    scheduleScan(view, attempt = 0)
                }
            }
        }
    }

    // ============================================================
    //  الالتقاط المتكرر — backoff تصاعدي
    // ============================================================

    private fun scheduleScan(view: WebView?, attempt: Int) {
        if (view == null || finished.get()) return
        if (attempt >= MAX_SCAN_ATTEMPTS) {
            Log.d(TAG, "انتهت محاولات المسح بدون نتيجة")
            return
        }
        // فواصل: 400ms, 700ms, 1000ms, 1300ms... حتى ~8 ثوان
        val delayMs = 400L + (attempt * 300L)

        mainHandler.postDelayed({
            if (finished.get() || view !== webView) return@postDelayed
            try {
                view.evaluateJavascript(buildScanScript()) { result ->
                    val url = parseScanResult(result)
                    if (url != null && isDirectVideoUrl(url)) {
                        Log.d(TAG, "التقطت عبر المسح #$attempt: $url")
                        finishWith { it.onDirectVideo(url, buildHeaders()) }
                        return@evaluateJavascript
                    }
                    // لم نجد — أعد المسح
                    scheduleScan(view, attempt + 1)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "فشل المسح #$attempt", t)
                scheduleScan(view, attempt + 1)
            }
        }, delayMs)
    }

    private fun parseScanResult(result: String?): String? {
        if (result.isNullOrBlank()) return null
        val cleaned = result.trim('"', ' ', '\n', '\t')
        if (cleaned.isEmpty() || cleaned == "null") return null
        // إذا أرجع السكربت مصفوفة مفصولة بفواصل — خذ الأول
        return cleaned.split("|||").firstOrNull()?.takeIf { it.startsWith("http") }
    }

    private fun buildHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to originalUrl,
        "Accept" to "*/*",
        "Accept-Language" to "ar,en;q=0.9",
        "Origin" to runCatching { java.net.URI(originalUrl).let { "${it.scheme}://${it.host}" } }
            .getOrDefault("")
    )

    private fun isDirectVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (lower.startsWith("data:") || lower.startsWith("blob:")) return false
        // ✅ يقبل .m3u8?token=xxx أيضاً
        return DIRECT_VIDEO_EXTENSIONS.any { lower.contains(it) }
    }

    private fun shortHost(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    // ============================================================
    //  سكربت الحقن المبكر — hook fetch/XHR/MediaSource
    // ============================================================

    private val EARLY_HOOK_SCRIPT = """
        (function() {
            if (window.__A4UP_HOOKED__) return;
            window.__A4UP_HOOKED__ = true;
            window.__A4UP_FOUND__ = [];

            function a4upReport(u) {
                if (!u) return;
                u = String(u);
                if (u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
                var low = u.toLowerCase();
                if (low.indexOf('.m3u8') === -1 &&
                    low.indexOf('.mp4') === -1 &&
                    low.indexOf('.mpd') === -1 &&
                    low.indexOf('.webm') === -1) return;
                if (window.__A4UP_FOUND__.indexOf(u) !== -1) return;
                window.__A4UP_FOUND__.push(u);
                try { console.log('[A4UP]FOUND:' + u); } catch(e) {}
            }

            // 1) fetch
            try {
                var _fetch = window.fetch;
                window.fetch = function(input) {
                    try {
                        var u = (typeof input === 'string') ? input
                                : (input && input.url) ? input.url : '';
                        a4upReport(u);
                    } catch(e) {}
                    return _fetch.apply(this, arguments);
                };
            } catch(e) {}

            // 2) XMLHttpRequest.open
            try {
                var _open = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    try { a4upReport(url); } catch(e) {}
                    return _open.apply(this, arguments);
                };
            } catch(e) {}

            // 3) HTMLMediaElement.src
            try {
                var _srcDesc = Object.getOwnPropertyDescriptor(
                    HTMLMediaElement.prototype, 'src');
                if (_srcDesc && _srcDesc.set) {
                    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                        configurable: true,
                        get: _srcDesc.get,
                        set: function(v) {
                            try { a4upReport(v); } catch(e) {}
                            return _srcDesc.set.call(this, v);
                        }
                    });
                }
            } catch(e) {}

            // 4) MediaSource.addSourceBuffer
            try {
                if (window.MediaSource && MediaSource.prototype.addSourceBuffer) {
                    var _addSB = MediaSource.prototype.addSourceBuffer;
                    MediaSource.prototype.addSourceBuffer = function(mime) {
                        try { console.log('[A4UP]MIME:' + mime); } catch(e) {}
                        return _addSB.apply(this, arguments);
                    };
                }
            } catch(e) {}

            // 5) jwplayer config
            try {
                if (window.jwplayer) {
                    var _jw = window.jwplayer;
                    window.jwplayer = function() {
                        var inst = _jw.apply(this, arguments);
                        try {
                            if (inst && inst.on) {
                                inst.on('ready', function() {
                                    try {
                                        var item = inst.getPlaylistItem && inst.getPlaylistItem();
                                        if (item && item.file) a4upReport(item.file);
                                        if (item && item.sources) {
                                            for (var i = 0; i < item.sources.length; i++) {
                                                if (item.sources[i].file) a4upReport(item.sources[i].file);
                                            }
                                        }
                                    } catch(e) {}
                                });
                            }
                        } catch(e) {}
                        return inst;
                    };
                }
            } catch(e) {}
        })();
    """.trimIndent()

    // ============================================================
    //  سكربت المسح المتكرر — DOM + jwplayer + videojs + regex
    // ============================================================

    private fun buildScanScript(): String = """
        (function() {
            try {
                // 1) ما التقطته الـ hooks المبكرة
                if (window.__A4UP_FOUND__ && window.__A4UP_FOUND__.length > 0) {
                    return window.__A4UP_FOUND__.join('|||');
                }

                var found = [];

                // 2) <video src> و <source>
                try {
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        if (v.src) found.push(v.src);
                        if (v.currentSrc) found.push(v.currentSrc);
                        var srcs = v.querySelectorAll('source');
                        for (var s = 0; s < srcs.length; s++) {
                            if (srcs[s].src) found.push(srcs[s].src);
                        }
                    }
                } catch(e) {}

                // 3) jwplayer
                try {
                    if (window.jwplayer && typeof window.jwplayer === 'function') {
                        var jw = window.jwplayer();
                        if (jw && jw.getPlaylistItem) {
                            var it = jw.getPlaylistItem();
                            if (it && it.file) found.push(it.file);
                            if (it && it.sources) {
                                for (var j = 0; j < it.sources.length; j++) {
                                    if (it.sources[j].file) found.push(it.sources[j].file);
                                }
                            }
                        }
                    }
                } catch(e) {}

                // 4) videojs
                try {
                    if (window.videojs && window.videojs.getAllPlayers) {
                        var players = window.videojs.getAllPlayers();
                        for (var p = 0; p < players.length; p++) {
                            try {
                                var u = players[p].currentSrc && players[p].currentSrc();
                                if (u) found.push(u);
                                var s = players[p].src && players[p].src();
                                if (typeof s === 'string') found.push(s);
                            } catch(e) {}
                        }
                    }
                } catch(e) {}

                // 5) فحص HTML كامل — بما يشمل <script> والـ inline data
                try {
                    var html = document.documentElement.innerHTML;
                    // يقبل m3u8 مع query params أو بدون
                    var re = /(https?:\/\/[^"'\s<>\\]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^"'\s<>\\]*)?)/gi;
                    var m;
                    while ((m = re.exec(html)) !== null) found.push(m[1]);
                } catch(e) {}

                // 6) فحص window scope — بعض المواقع تخزن الرابط في متغير عام
                try {
                    var keys = ['videoUrl', 'file', 'source', 'hls', 'streamUrl',
                                'videoSrc', 'playerSrc', 'mediaUrl', 'm3u8'];
                    for (var k = 0; k < keys.length; k++) {
                        try {
                            var val = window[keys[k]];
                            if (typeof val === 'string' && val.indexOf('http') === 0) {
                                found.push(val);
                            }
                        } catch(e) {}
                    }
                } catch(e) {}

                // تنظيف + إزالة التكرار
                var seen = {};
                var unique = [];
                for (var x = 0; x < found.length; x++) {
                    var u2 = String(found[x]).trim();
                    if (!u2) continue;
                    if (u2.indexOf('blob:') === 0) continue;
                    if (u2.indexOf('data:') === 0) continue;
                    if (seen[u2]) continue;
                    seen[u2] = true;
                    unique.push(u2);
                }

                // أول رابط مطابق
                for (var f = 0; f < unique.length; f++) {
                    var low = unique[f].toLowerCase();
                    if (low.indexOf('.m3u8') !== -1 ||
                        low.indexOf('.mp4') !== -1 ||
                        low.indexOf('.mpd') !== -1 ||
                        low.indexOf('.webm') !== -1) {
                        return unique[f];
                    }
                }
                return '';
            } catch (err) {
                return '';
            }
        })();
    """.trimIndent()

    // ============================================================
    //  المهلة والإنهاء
    // ============================================================

    private fun setupTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            if (!finished.get()) {
                Log.w(TAG, "انتهت المهلة (${timeoutMs}ms)")
                val captured = interceptedUrls.peek()
                finishWith { cb ->
                    if (captured != null) {
                        cb.onDirectVideo(captured, buildHeaders())
                    } else {
                        cb.onIframeFallback(originalUrl)
                    }
                }
            }
        }
    }

    private fun finishWith(action: (ExtractionCallback) -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        timeoutJob?.cancel()
        timeoutJob = null
        val cb = currentCallback ?: return
        mainHandler.post {
            try {
                action(cb)
            } catch (t: Throwable) {
                Log.e(TAG, "خطأ في callback", t)
            }
            destroy()
        }
    }
}
