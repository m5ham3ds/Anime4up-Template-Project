package com.aistudio.cinestream.xyzabc.extensions.anime4up.providers

import com.aistudio.cinestream.xyzabc.extensions.anime4up.ProviderExtension
import java.net.URLEncoder

class Anime4upExtension : ProviderExtension {

    override val id: String = "anime4up"
    override val name: String = "أنمي فور أب"
    override val baseUrl: String = "https://w1.anime4up.rest"
    override val isAnime: Boolean = true
    override val isMovie: Boolean = true
    override val isSeries: Boolean = true
    override val lang: String = "ar"
    override val iconUrl: String =
        "https://w1.anime4up.rest/wp-content/uploads/2019/03/Anime4up-Icon-1.png"

    // ============================================================
    //  البحث
    // ============================================================
    override fun getSearchUrl(titleOriginal: String, titleClean: String): String {
        return "$baseUrl/?s=" + URLEncoder.encode(titleClean, "UTF-8")
    }

    // ============================================================
    //  سكريبت الاستخراج الرئيسي
    //  ⚠️ قاعدة ذهبية: المصدر الوحيد هو ul#episode-servers li[data-watch]
    //  ⚠️ ممنوع لمس #download أو .download-list أو <table> نهائياً
    // ============================================================
    override fun getExtractionScript(
        isMovie: Boolean,
        episode: Int,
        title: String
    ): String {
        val safeTitle = escapeForJs(title)

        return """
            (function() {
                'use strict';

                // ============================================
                //  إعدادات
                // ============================================
                var A4UP_TITLE         = "$safeTitle";
                var A4UP_EPISODE       = $episode;
                var A4UP_IS_MOVIE      = $isMovie;

                var A4UP_SENT          = false;
                var A4UP_MAX_ATTEMPTS  = 30;   // 30 × 500ms = 15 ثانية
                var A4UP_INTERVAL_MS   = 500;

                // ============================================
                //  إرسال النتائج للتطبيق
                // ============================================
                function a4upSend(items) {
                    if (A4UP_SENT) return;
                    if (typeof AndroidBridge === 'undefined') return;
                    A4UP_SENT = true;
                    try {
                        if (items && items.length > 0) {
                            AndroidBridge.sendServersV2(
                                JSON.stringify(items),
                                window.location.href
                            );
                        } else {
                            AndroidBridge.sendFailed();
                        }
                    } catch (e) {
                        try { AndroidBridge.sendFailed(); } catch (_) {}
                    }
                }

                // ============================================
                //  أدوات مساعدة
                // ============================================
                function a4upClean(txt) {
                    if (!txt) return '';
                    return String(txt).replace(/\s+/g, ' ').trim();
                }

                function a4upExtractEpisodeNumber(text) {
                    if (!text) return 0;
                    var arabic = '٠١٢٣٤٥٦٧٨٩';
                    var normalized = String(text).replace(/[٠-٩]/g, function(d) {
                        return arabic.indexOf(d);
                    });
                    var m = normalized.match(/(\d+)/);
                    return m ? parseInt(m[1], 10) : 0;
                }

                // ============================================
                //  فلتر روابط التنزيل — صرامة عالية جداً
                // ============================================
                function a4upIsDownloadUrl(url) {
                    if (!url) return true;
                    var u = String(url).toLowerCase();

                    // 1) نطاقات تنزيل معروفة (مع مسارات /d/ أو /download)
                    var downloadPatterns = [
                        'megamax.me/d/',
                        'megamax.me/download',
                        'mega.nz/#!',
                        'mega.nz/#F!',
                        'gofile.io/d/',
                        'file-upload.org',
                        'mediafire.com/file',
                        'workupload.com/file',
                        'dsvplay.com/d/',
                        'streamruby.com/d/',
                        'mp4upload.com/d/',
                        'uqload.is/d/',
                        'uqload.vc/d/'
                    ];
                    for (var i = 0; i < downloadPatterns.length; i++) {
                        if (u.indexOf(downloadPatterns[i]) !== -1) return true;
                    }

                    // 2) مسارات تنزيل عامة
                    if (u.indexOf('/download/') !== -1) return true;
                    if (u.indexOf('/download?') !== -1) return true;

                    return false;
                }

                // ============================================
                //  اختيار أفضل تطابق من نتائج البحث
                // ============================================
                function a4upFindBestMatch(searchTitle) {
                    var cards = document.querySelectorAll('.anime-card-themex');
                    if (!cards || cards.length === 0) {
                        cards = document.querySelectorAll('.anime-card-container');
                    }
                    if (!cards || cards.length === 0) return null;

                    var words = String(searchTitle).toLowerCase()
                        .split(/[\s:\.\-–—,،\(\)\[\]]+/)
                        .filter(function(w) { return w.length > 1; });

                    var bestLink  = null;
                    var bestScore = -1;
                    var firstLink = null;

                    for (var i = 0; i < cards.length; i++) {
                        var card = cards[i];
                        var isEpisodeCard = card.querySelector('.ep_num') !== null;

                        var linkEl = card.querySelector('.anime-card-poster a.overlay')
                                  || card.querySelector('.anime-card-title a')
                                  || card.querySelector('a.overlay')
                                  || card.querySelector('a[href*="/anime/"]');
                        var titleEl = card.querySelector('.anime-card-title h3 a')
                                   || card.querySelector('.anime-card-title h3')
                                   || card.querySelector('h3 a');
                        if (!linkEl) continue;

                        var href = linkEl.getAttribute('href') || '';
                        var isAnimeLink = href.indexOf('/anime/') !== -1;
                        if (!firstLink && isAnimeLink) firstLink = linkEl;

                        var t = (titleEl ? titleEl.innerText : '').toLowerCase();
                        var sc = 0;
                        for (var w = 0; w < words.length; w++) {
                            if (t.indexOf(words[w]) !== -1) sc++;
                        }
                        if (isAnimeLink)   sc += 0.5;
                        if (isEpisodeCard) sc -= 0.3;
                        sc = sc - (i * 0.01);

                        if (sc > bestScore) {
                            bestScore = sc;
                            bestLink  = linkEl;
                        }
                    }

                    if (words.length > 0 && bestScore >= 1) return bestLink;
                    return firstLink || bestLink;
                }

                // ============================================
                //  المرحلة 1: صفحة البحث
                // ============================================
                function a4upHandleSearch() {
                    if (!A4UP_TITLE) { a4upSend([]); return; }

                    var attempts = 0;
                    var max = 20;
                    var iv = setInterval(function() {
                        attempts++;
                        var link = a4upFindBestMatch(A4UP_TITLE);
                        if (link && link.href) {
                            clearInterval(iv);
                            window.location.href = link.href;
                            return;
                        }
                        if (attempts >= max) {
                            clearInterval(iv);
                            a4upSend([]);
                        }
                    }, 500);
                }

                // ============================================
                //  المرحلة 2: صفحة الأنمي
                // ============================================
                function a4upHandleAnimePage() {
                    if (document.querySelector('#episode-servers')) {
                        a4upHandleEpisodePage();
                        return;
                    }

                    var targetEp = parseInt(A4UP_EPISODE) || 1;

                    var anchors = document.querySelectorAll(
                        '#episodesList .anime-card-themex .ep_num a, ' +
                        '#episodesList .anime-card-themex a.overlay'
                    );
                    if (!anchors || anchors.length === 0) {
                        anchors = document.querySelectorAll(
                            '#ULEpisodesList li a, .all-episodes-list li a'
                        );
                    }
                    if (!anchors || anchors.length === 0) {
                        a4upSend([]);
                        return;
                    }

                    // إزالة التكرار
                    var seen = {};
                    var unique = [];
                    for (var i = 0; i < anchors.length; i++) {
                        var h = anchors[i].getAttribute('href') || '';
                        if (h && !seen[h]) {
                            seen[h] = true;
                            unique.push(anchors[i]);
                        }
                    }
                    anchors = unique;

                    if (A4UP_IS_MOVIE) {
                        try { anchors[0].click(); }
                        catch (e) { window.location.href = anchors[0].href; }
                        return;
                    }

                    var found = null;
                    for (var j = 0; j < anchors.length; j++) {
                        var txt = a4upClean(
                            anchors[j].textContent || anchors[j].innerText || ''
                        );
                        var num = a4upExtractEpisodeNumber(txt);
                        if (num === targetEp) { found = anchors[j]; break; }
                    }

                    var chosen = found || anchors[0];
                    if (chosen) {
                        try { chosen.click(); }
                        catch (e) { window.location.href = chosen.href; }
                    } else {
                        a4upSend([]);
                    }
                }

                // ============================================
                //  المرحلة 3: صفحة الحلقة (الأهم)
                //  المصدر: #episode-servers li[data-watch] حصراً
                // ============================================
                function a4upHandleEpisodePage() {
                    var attempt = 0;

                    function tryExtract() {
                        attempt++;

                        var container = document.getElementById('episode-servers');
                        if (!container) {
                            if (attempt < A4UP_MAX_ATTEMPTS) {
                                setTimeout(tryExtract, A4UP_INTERVAL_MS);
                            } else {
                                a4upSend([]);
                            }
                            return;
                        }

                        var items = [];
                        var lis = container.querySelectorAll(':scope > li');
                        if (!lis || lis.length === 0) {
                            lis = container.querySelectorAll('li');
                        }

                        for (var i = 0; i < lis.length; i++) {
                            var li = lis[i];

                            // (أ) يجب أن يحتوي data-watch
                            if (!li.hasAttribute('data-watch')) continue;

                            var watchUrl = a4upClean(li.getAttribute('data-watch'));
                            if (!watchUrl || watchUrl.indexOf('http') !== 0) continue;

                            // (ب) استبعاد روابط التنزيل
                            if (a4upIsDownloadUrl(watchUrl)) continue;

                            // (ج) الاسم
                            var nameEl = li.querySelector('.watch-server-name');
                            var name = nameEl
                                ? a4upClean(nameEl.textContent || nameEl.innerText)
                                : '';
                            if (!name) {
                                var alt = li.querySelector('.ser, .server-name');
                                if (alt) name = a4upClean(alt.textContent || alt.innerText);
                            }
                            if (!name) name = 'سيرفر ' + (i + 1);

                            // (د) الجودة
                            var qEl = li.querySelector('.quality');
                            var quality = qEl
                                ? a4upClean(qEl.textContent || qEl.innerText)
                                : '';

                            // (هـ) مميز
                            var featured = li.classList.contains('watch-server-featured');

                            // (و) منع التكرار
                            var dup = false;
                            for (var r = 0; r < items.length; r++) {
                                if (items[r].url === watchUrl) { dup = true; break; }
                            }
                            if (dup) continue;

                            // ⚠️ المفاتيح بالإنجليزية لتطابق ServerJsonParser
                            items.push({
                                name: name,
                                url: watchUrl,
                                quality: quality,
                                featured: featured
                            });
                        }

                        if (items.length > 0) {
                            a4upSend(items);
                            return;
                        }

                        if (attempt < A4UP_MAX_ATTEMPTS) {
                            setTimeout(tryExtract, A4UP_INTERVAL_MS);
                        } else {
                            a4upSend([]);
                        }
                    }

                    tryExtract();
                }

                // ============================================
                //  تحديد نوع الصفحة والبدء
                // ============================================
                function a4upBoot() {
                    var host   = window.location.hostname;
                    var path   = window.location.pathname;
                    var search = window.location.search || '';

                    if (host.indexOf('anime4up') === -1) {
                        a4upSend([]);
                        return;
                    }

                    try {
                        // 1) صفحة الحلقة
                        if (document.querySelector('#episode-servers') ||
                            path.indexOf('/episode/') !== -1) {
                            a4upHandleEpisodePage();
                            return;
                        }

                        // 2) صفحة البحث
                        if (/[?&]s=/.test(search)) {
                            a4upHandleSearch();
                            return;
                        }

                        // 3) صفحة الأنمي
                        if (document.querySelector('#episodesList') ||
                            document.querySelector('.anime-info-container') ||
                            path.indexOf('/anime/') !== -1) {
                            a4upHandleAnimePage();
                            return;
                        }

                        a4upSend([]);
                    } catch (err) {
                        a4upSend([]);
                    }
                }

                setTimeout(a4upBoot, 800);
            })();
        """.trimIndent()
    }

    // ============================================================
    //  Helper: تنظيف النص لتمريره داخل JavaScript
    // ============================================================
    private fun escapeForJs(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
