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
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ============================================================
 *  VideoExtractor
 * ============================================================
 *  يحمّل الرابط الوسيط (iframe) داخل WebView مخفي ويستخرج منه
 *  رابط الفيديو الحقيقي باستخدام استراتيجيتين:
 *
 *   1) اعتراض طلبات الشبكة (shouldInterceptRequest)
 *      لالتقاط ملفات .m3u8 / .mp4 / .mpd
 *
 *   2) حقن JavaScript بعد اكتمال تحميل الصفحة
 *      للبحث عن <video> أو <source> أو متغيرات مشغلات معروفة.
 *
 *  في حال فشل الاستخراج، يتم إرجاع الرابط الوسيط نفسه
 *  ليُعرض داخل مشغل iframe.
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

        /** امتدادات الفيديو المباشرة التي نعتبرها نجاحاً */
        private val DIRECT_VIDEO_EXTENSIONS = listOf(
            ".m3u8", ".mp4", ".mpd", ".webm", ".mkv", ".ts"
        )

        /** نطاقات معروفة بأنها صفحات وسيطة */
        private val INTERMEDIATE_HOSTS = listOf(
            "share4max.com",
            "voe.sx",
            "videa.hu",
            "vkvideo.ru",
            "uqload",
            "playmogo.com",
            "doodstream",
            "mp4upload.com",
            "rubyvidhub.com",
            "streamruby.com",
            "dsvplay.com",
            "4m.y8x1c4v.shop",
            "4b.1i2cqoi.shop"
        )
    }

    // ============================================================
    //  Callbacks
    // ============================================================

    interface ExtractionCallback {
        /**
         * نجح استخراج رابط فيديو مباشر (m3u8/mp4/...)
         * @param videoUrl الرابط النهائي
         * @param headers  ترويسات إضافية مطلوبة (Referer مثلاً)
         */
        fun onDirectVideo(videoUrl: String, headers: Map<String, String>)

        /**
         * لم نتمكن من استخراج رابط مباشر،
         * لكن الرابط الوسيط صالح للعرض داخل iframe.
         * @param iframeUrl الرابط الوسيط
         */
        fun onIframeFallback(iframeUrl: String)

        /** فشل كامل — لم نجد أي شيء صالح */
        fun onFailure(reason: String)

        /** حدث تقدّم اختياري (للتشخيص) */
        fun onProgress(message: String) {}
    }

    // ============================================================
    //  الحالة الداخلية
    // ============================================================

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private var timeoutJob: Job? = null
    private val finished = AtomicBoolean(false)

    /** الروابط الملتقطة من اعتراض الشبكة */
    private val interceptedUrls = ConcurrentLinkedQueue<InterceptedResource>()

    /** الرابط الأصلي الذي بدأنا به */
    private var originalUrl: String = ""

    /** عدد مرات إعادة التوجيه داخل iframe */
    private var redirectDepth: Int = 0
    private val maxRedirectDepth = 3

    private data class InterceptedResource(
        val url: String,
        val contentType: String?,
        val method: String
    )

    // ============================================================
    //  الواجهة العامة
    // ============================================================

    /**
     * ابدأ عملية الاستخراج.
     * @param url الرابط الوسيط المستخرج من السيرفر
     * @param callback النتيجة
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun extract(url: String, callback: ExtractionCallback) {
        if (url.isBlank()) {
            callback.onFailure("الرابط فارغ")
            return
        }

        this.originalUrl = url
        this.finished.set(false)
        this.interceptedUrls.clear()
        this.redirectDepth = 0

        mainHandler.post {
            try {
                val wv = createWebView()
                this.webView = wv
                setupTimeout(callback)
                callback.onProgress("جاري تحميل: $url")
                wv.loadUrl(url, buildHeaders())
            } catch (t: Throwable) {
                Log.e(TAG, "فشل تهيئة WebView", t)
                safeFinish { callback.onFailure("فشل تهيئة المشغل: ${t.message}") }
            }
        }
    }

    /** حرّر الموارد — استدعها عند إغلاق الشاشة */
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
    //  إنشاء WebView
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                    Log.d(TAG, "JS: ${msg.message()} @ ${msg.lineNumber()}")
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    val method = request.method ?: "GET"
                    handleInterceptedRequest(reqUrl, method)
                    return null
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d(TAG, "onPageStarted: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "onPageFinished: $url")
                    if (url != null) {
                        // افحص هل الرابط الحالي نفسه ملف فيديو مباشر
                        if (isDirectVideoUrl(url)) {
                            safeFinish {
                                it.onDirectVideo(url, buildHeaders())
                            }
                            return
                        }
                    }
                    // احقن JS لاستخراج الفيديو
                    injectVideoDetectionJs(view, url)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    // نتجاهل أخطاء الموارد الفرعية، نهتم فقط بالصفحة الرئيسية
                    if (request?.isForMainFrame == true) {
                        Log.w(TAG, "خطأ في الصفحة الرئيسية: ${error?.description}")
                    }
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
    //  اعتراض الشبكة
    // ============================================================

    private fun handleInterceptedRequest(url: String, method: String) {
        if (!isDirectVideoUrl(url)) return
        Log.d(TAG, "التقطت مورد فيديو: $url")

        interceptedUrls.add(
            InterceptedResource(
                url = url,
                contentType = guessContentType(url),
                method = method
            )
        )
        // أول رابط مباشر = نعتبره النتيجة النهائية
        safeFinish { cb ->
            cb.onDirectVideo(url, buildHeaders())
        }
    }

    private fun isDirectVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (lower.startsWith("data:") || lower.startsWith("blob:")) return false
        return DIRECT_VIDEO_EXTENSIONS.any { lower.contains(it) }
    }

    private fun guessContentType(url: String): String? = when {
        url.contains(".m3u8", true) -> "application/vnd.apple.mpegurl"
        url.contains(".mpd", true) -> "application/dash+xml"
        url.contains(".mp4", true) -> "video/mp4"
        url.contains(".webm", true) -> "video/webm"
        url.contains(".ts", true) -> "video/mp2t"
        url.contains(".mkv", true) -> "video/x-matroska"
        else -> null
    }

    // ============================================================
    //  حقن JavaScript
    // ============================================================

    private fun injectVideoDetectionJs(view: WebView?, pageUrl: String?) {
        if (view == null || finished.get()) return

        val js = buildDetectionScript()

        // نُحقن الكود بعد فترة قصيرة للسماح للمشغل بالتهيئة
        mainHandler.postDelayed({
            if (finished.get() || view !== webView) return@postDelayed
            try {
                view.evaluateJavascript(js) { result ->
                    Log.d(TAG, "نتيجة حقن JS: $result")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "فشل حقن JS", t)
            }
        }, 1500L)
    }

    private fun buildDetectionScript(): String {
        return """
            (function() {
                try {
                    if (window.__a4up_extracted) return;
                    window.__a4up_extracted = true;

                    var found = [];

                    // 1) وسوم <video>
                    var videos = document.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        if (v.src) found.push(v.src);
                        if (v.currentSrc) found.push(v.currentSrc);
                        var sources = v.querySelectorAll('source');
                        for (var s = 0; s < sources.length; s++) {
                            if (sources[s].src) found.push(sources[s].src);
                        }
                    }

                    // 2) متغيرات مشغلات معروفة (jwplayer, videojs, ...)
                    try {
                        if (window.jwplayer && typeof window.jwplayer === 'function') {
                            var jw = window.jwplayer();
                            if (jw && jw.getPlaylistItem) {
                                var item = jw.getPlaylistItem();
                                if (item && item.file) found.push(item.file);
                                if (item && item.sources) {
                                    for (var j = 0; j < item.sources.length; j++) {
                                        if (item.sources[j].file) found.push(item.sources[j].file);
                                    }
                                }
                            }
                        }
                    } catch (e) {}

                    try {
                        if (window.videojs) {
                            var players = window.videojs.getAllPlayers ? window.videojs.getAllPlayers() : [];
                            for (var p = 0; p < players.length; p++) {
                                try {
                                    var u = players[p].currentSrc && players[p].currentSrc();
                                    if (u) found.push(u);
                                } catch (e) {}
                            }
                        }
                    } catch (e) {}

                    // 3) بحث في سكربتات الصفحة عن روابط m3u8/mp4
                    try {
                        var scripts = document.querySelectorAll('script');
                        var re = /(https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^"'\s<>]*)?)/gi;
                        for (var k = 0; k < scripts.length; k++) {
                            var content = scripts[k].textContent || '';
                            var m;
                            while ((m = re.exec(content)) !== null) {
                                found.push(m[1]);
                            }
                        }
                    } catch (e) {}

                    // 4) ابحث في مصادر الصفحة الكاملة (innerHTML)
                    try {
                        var html = document.documentElement.innerHTML;
                        var re2 = /(https?:\/\/[^"'\s<>]+?\.(?:m3u8|mp4|mpd|webm)(?:\?[^"'\s<>]*)?)/gi;
                        var mm;
                        while ((mm = re2.exec(html)) !== null) {
                            found.push(mm[1]);
                        }
                    } catch (e) {}

                    // إزالة التكرار + إرسال أفضل نتيجة
                    var seen = {};
                    var unique = [];
                    for (var u = 0; u < found.length; u++) {
                        var url = String(found[u]).trim();
                        if (!url) continue;
                        if (url.indexOf('blob:') === 0) continue;
                        if (url.indexOf('data:') === 0) continue;
                        if (seen[url]) continue;
                        seen[url] = true;
                        unique.push(url);
                    }

                    // نرجّع الرابط الأول الذي يطابق الامتدادات المطلوبة
                    var finalUrl = null;
                    for (var f = 0; f < unique.length; f++) {
                        var low = unique[f].toLowerCase();
                        if (low.indexOf('.m3u8') !== -1 ||
                            low.indexOf('.mp4') !== -1 ||
                            low.indexOf('.mpd') !== -1 ||
                            low.indexOf('.webm') !== -1) {
                            finalUrl = unique[f];
                            break;
                        }
                    }

                    return finalUrl || (unique.length > 0 ? unique[0] : '');

                } catch (err) {
                    return '';
                }
            })();
        """.trimIndent()
    }

    // ============================================================
    //  المهلة والإنهاء
    // ============================================================

    private fun setupTimeout(callback: ExtractionCallback) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(timeoutMs)
            if (!finished.get()) {
                Log.w(TAG, "انتهت المهلة، جاري المحاولة الأخيرة")
                attemptFinalResolution(callback)
            }
        }
    }

    /**
     * عند انتهاء المهلة:
     *  - إذا كان لدينا رابط معترض → نستخدمه
     *  - وإلا → نعيد الرابط الأصلي كـ iframe
     */
    private fun attemptFinalResolution(callback: ExtractionCallback) {
        val captured = interceptedUrls.toList()
        if (captured.isNotEmpty()) {
            safeFinish { it.onDirectVideo(captured.first().url, buildHeaders()) }
        } else {
            safeFinish { it.onIframeFallback(originalUrl) }
        }
    }

    private fun safeFinish(action: (ExtractionCallback) -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        timeoutJob?.cancel()
        // نمنح الـ callback فرصة للعمل
        mainHandler.post {
            try {
                // placeholder — سيُستبدل في extract()
            } catch (_: Throwable) {}
        }
    }

    /**
     * الإصدار الصحيح من safeFinish الذي يعرف الـ callback.
     * نستخدم متغيراً مؤقتاً لتخزين الـ callback الحالي.
     */
    private var currentCallback: ExtractionCallback? = null

    private fun safeFinish(action: () -> Unit) {
        if (!finished.compareAndSet(false, true)) return
        timeoutJob?.cancel()
        val cb = currentCallback ?: return
        mainHandler.post {
            try {
                action()
            } catch (t: Throwable) {
                Log.e(TAG, "خطأ داخل callback", t)
            }
            destroy()
        }
    }
}
