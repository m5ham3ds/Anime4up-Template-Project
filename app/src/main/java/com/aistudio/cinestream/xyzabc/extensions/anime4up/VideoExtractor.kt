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

/**
 * ============================================================
 *  VideoExtractor — النسخة النهائية المصححة
 * ============================================================
 */
class VideoExtractor(
    private val context: Context,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "VideoExtractor"

        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        const val DEFAULT_TIMEOUT_MS = 25_000L

        private val DIRECT_VIDEO_EXTENSIONS = listOf(
            ".m3u8", ".mp4", ".mpd", ".webm", ".mkv", ".ts"
        )
    }

    // ============================================================
    //  Callback
    // ============================================================

    interface ExtractionCallback {
        fun onDirectVideo(videoUrl: String, headers: Map<String, String>)
        fun onIframeFallback(iframeUrl: String)
        fun onFailure(reason: String)
        fun onProgress(message: String) {}
    }

    // ============================================================
    //  الحالة
    // ============================================================

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
                callback.onProgress("جاري تحميل: $url")
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
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                    Log.d(TAG, "JS: ${msg.message()}")
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (isDirectVideoUrl(reqUrl)) {
                        Log.d(TAG, "التقطت مورد فيديو: $reqUrl")
                        interceptedUrls.add(reqUrl)
                        finishWith { it.onDirectVideo(reqUrl, buildHeaders()) }
                    }
                    return null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "onPageStarted: $url")
                    if (url != null && isDirectVideoUrl(url)) {
                        finishWith { it.onDirectVideo(url, buildHeaders()) }
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "onPageFinished: $url")
                    if (url != null && isDirectVideoUrl(url)) {
                        finishWith { it.onDirectVideo(url, buildHeaders()) }
                        return
                    }
                    injectDetectionJs(view)
                }
            }
        }
    }

    private fun buildHeaders(): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Referer" to originalUrl,
        "Accept" to "*/*",
        "Accept-Language" to "ar,en;q=0.9"
    )

    // ============================================================
    //  كشف الفيديو
    // ============================================================

    private fun isDirectVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (lower.startsWith("data:") || lower.startsWith("blob:")) return false
        return DIRECT_VIDEO_EXTENSIONS.any { lower.contains(it) }
    }

    private fun injectDetectionJs(view: WebView?) {
        if (view == null || finished.get()) return
        mainHandler.postDelayed({
            if (finished.get() || view !== webView) return@postDelayed
            try {
                view.evaluateJavascript(buildDetectionScript()) { result ->
                    Log.d(TAG, "نتيجة الحقن: $result")
                    val cleaned = result?.trim('"', ' ', '\n') ?: return@evaluateJavascript
                    if (cleaned.isNotEmpty() &&
                        cleaned != "null" &&
                        isDirectVideoUrl(cleaned)
                    ) {
                        finishWith { it.onDirectVideo(cleaned, buildHeaders()) }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "فشل الحقن", t)
            }
        }, 1500L)
    }

    private fun buildDetectionScript(): String = """
        (function() {
            try {
                var found = [];

                // 1) video tags
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

                // 2) jwplayer
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
                } catch (e) {}

                // 3) videojs
                try {
                    if (window.videojs && window.videojs.getAllPlayers) {
                        var players = window.videojs.getAllPlayers();
                        for (var p = 0; p < players.length; p++) {
                            try {
                                var u = players[p].currentSrc && players[p].currentSrc();
                                if (u) found.push(u);
                            } catch (e) {}
                        }
                    }
                } catch (e) {}

                // 4) ابحث في innerHTML
                try {
                    var html = document.documentElement.innerHTML;
                    var re = /(https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^"'\s<>]*)?)/gi;
                    var m;
                    while ((m = re.exec(html)) !== null) found.push(m[1]);
                } catch (e) {}

                // إزالة التكرار
                var seen = {};
                var unique = [];
                for (var k = 0; k < found.length; k++) {
                    var u2 = String(found[k]).trim();
                    if (!u2) continue;
                    if (u2.indexOf('blob:') === 0) continue;
                    if (u2.indexOf('data:') === 0) continue;
                    if (seen[u2]) continue;
                    seen[u2] = true;
                    unique.push(u2);
                }

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
                Log.w(TAG, "انتهت المهلة")
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
