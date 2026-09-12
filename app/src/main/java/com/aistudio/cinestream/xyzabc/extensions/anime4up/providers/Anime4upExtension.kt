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
    // رابط البحث
    // ============================================================
    override fun getSearchUrl(titleOriginal: String, titleClean: String): String {
        return "$baseUrl/?s=" + URLEncoder.encode(titleClean, "UTF-8")
    }

    // ============================================================
    // سكريبت الاستخراج
    // ============================================================
    override fun getExtractionScript(
        isMovie: Boolean,
        episode: Int,
        title: String
    ): String {
        val safeTitle = escapeForJs(title)

        return """
            (function() {
                // ========== المتغيرات المستقبلة من Kotlin ==========
                window.a4upTargetTitle   = "$safeTitle";
                window.a4upTargetEpisode = $episode;
                window.a4upIsMovie       = $isMovie;
                window.a4upSent          = false;

                // ========== إرسال النتائج ==========
                function a4upSend(items) {
                    if (window.a4upSent) return;
                    if (typeof AndroidBridge === 'undefined') return;
                    window.a4upSent = true;
                    if (items && items.length > 0) {
                        AndroidBridge.sendServersV2(JSON.stringify(items), window.location.href);
                    } else {
                        AndroidBridge.sendFailed();
                    }
                }

                // ========== تنظيف النصوص ==========
                function a4upClean(txt) {
                    if (!txt) return '';
                    return txt.replace(/\s+/g, ' ').trim();
                }

                // ========== استثناء روابط التنزيل ==========
                // نقوم بفلترة صارمة للتأكد من أننا لا نمرر روابط تحميل أبدًا
                function a4upIsDownloadUrl(url) {
                    if (!url) return true;
                    var u = String(url).toLowerCase();

                    // 1) روابط التنزيل المباشرة (أسماء النطاقات)
                    var downloadDomains = [
                        'megamax.me',
                        'mega.nz',
                        'gofile.io',
                        'file-upload.org',
                        'dsvplay.com',
                        'streamruby.com',
                        'www.file-upload.org',
                        'www.mp4upload.com'
                    ];
                    for (var i = 0; i < downloadDomains.length; i++) {
                        if (u.indexOf(downloadDomains[i]) !== -1) return true;
                    }

                    // 2) مسارات التنزيل الشائعة
                    //    - /download/  أو  /d/  مع نطاقات hosts المشاهدة (uqload, mp4upload ...)
                    if (u.indexOf('/download/') !== -1) return true;
                    if (u.indexOf('mega.nz/#!') !== -1) return true;

                    // 3) محاولة تمييز أوضح: مسار `/d/` يكون عادةً تنزيلًا
                    //    بينما مسار `/e/` أو `/embed/` أو `/iframe/` يكون مشاهدة
                    //    نستثني فقط عندما يكون `/d/` في المسار بعد النطاق.
                    try {
                        var parsed = new URL(url, window.location.href);
                        var path = parsed.pathname.toLowerCase();
                        if (path.indexOf('/d/') === 0) return true;
                        if (path.indexOf('/download') === 0) return true;
                    } catch (e) { /* ignore */ }

                    return false;
                }

                // ========== اختيار أفضل تطابق من نتائج البحث ==========
                function a4upFindBestMatch(searchTitle) {
                    var cards = document.querySelectorAll('.anime-card-themex');
                    if (!cards || cards.length === 0) {
                        cards = document.querySelectorAll('.anime-card-container');
                    }
                    if (!cards || cards.length === 0) return null;

                    var words = searchTitle.toLowerCase()
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
                        if (isAnimeLink)     sc += 0.5;
                        if (isEpisodeCard)   sc -= 0.3;
                        sc = sc - (i * 0.01);

                        if (sc > bestScore) {
                            bestScore = sc;
                            bestLink  = linkEl;
                        }
                    }

                    if (words.length > 0 && bestScore >= 1) return bestLink;
                    return firstLink || bestLink;
                }

                // ========== المرحلة 1: صفحة البحث ==========
                function a4upHandleSearch() {
                    var searchTitle = window.a4upTargetTitle;
                    if (!searchTitle) { a4upSend([]); return; }

                    var attempts = 0;
                    var iv = setInterval(function() {
                        attempts++;
                        var link = a4upFindBestMatch(searchTitle);
                        if (link && link.href) {
                            clearInterval(iv);
                            window.location.href = link.href;
                            return;
                        }
                        if (attempts > 20) {
                            clearInterval(iv);
                            a4upSend([]);
                        }
                    }, 500);
                }

                // ========== المرحلة 2: صفحة الأنمي ==========
                function a4upHandleAnimePage() {
                    if (document.querySelector('#episode-servers')) {
                        a4upHandleEpisodePage();
                        return;
                    }

                    var targetEp = parseInt(window.a4upTargetEpisode) || 1;

                    var episodeAnchors = document.querySelectorAll(
                        '#episodesList .anime-card-themex .ep_num a, ' +
                        '#episodesList .anime-card-themex a.overlay'
                    );

                    if (!episodeAnchors || episodeAnchors.length === 0) {
                        episodeAnchors = document.querySelectorAll(
                            '#ULEpisodesList li a, .all-episodes-list li a'
                        );
                    }

                    if (!episodeAnchors || episodeAnchors.length === 0) {
                        a4upSend([]);
                        return;
                    }

                    // إزالة التكرار
                    var seen = {};
                    var uniqueAnchors = [];
                    for (var k = 0; k < episodeAnchors.length; k++) {
                        var href = episodeAnchors[k].getAttribute('href') || '';
                        if (href && !seen[href]) {
                            seen[href] = true;
                            uniqueAnchors.push(episodeAnchors[k]);
                        }
                    }
                    episodeAnchors = uniqueAnchors;

                    if (window.a4upIsMovie) {
                        episodeAnchors[0].click();
                        return;
                    }

                    var found = null;
                    var first = episodeAnchors[0];
                    for (var i = 0; i < episodeAnchors.length; i++) {
                        var a   = episodeAnchors[i];
                        var txt = a4upClean(a.textContent || a.innerText || '');
                        var match = txt.match(/(\d+)/);
                        if (match) {
                            var num = parseInt(match[1], 10);
                            if (num === targetEp) { found = a; break; }
                        }
                    }

                    var chosen = found || first;
                    if (chosen) chosen.click();
                    else a4upSend([]);
                }

                // ========== المرحلة 3: استخراج سيرفرات المشاهدة فقط ==========
                // ⚠️ قواعد صارمة:
                //   1) المصدر الوحيد هو #episode-servers
                //   2) كل عنصر يجب أن يحمل data-watch
                //   3) يُرفض أي رابط يطابق أنماط التنزيل
                //   4) لا fallback لالتقاط iframe عشوائي — أفضل أن نرسل فشل
                function a4upHandleEpisodePage() {
                    var attempts = 0;
                    var maxAttempts = 20; // ~10 ثوانٍ

                    function tryExtract() {
                        attempts++;

                        // 1) التحقق من وجود #episode-servers
                        var container = document.getElementById('episode-servers');
                        if (!container) {
                            if (attempts < maxAttempts) {
                                setTimeout(tryExtract, 500);
                            } else {
                                // لا يوجد حاوية سيرفرات — فشل نظيف
                                a4upSend([]);
                            }
                            return;
                        }

                        var items = [];
                        // فقط الأبناء المباشرون (li) لتفادي أي عنصر عشوائي
                        var lis = container.querySelectorAll(':scope > li');
                        if (!lis || lis.length === 0) {
                            lis = container.querySelectorAll('li');
                        }

                        for (var i = 0; i < lis.length; i++) {
                            var li = lis[i];
                            var watchUrl = li.getAttribute('data-watch');

                            // 2) تخطّي العناصر بدون data-watch
                            if (!watchUrl) continue;

                            // 3) يجب أن يكون رابط HTTP
                            if (watchUrl.indexOf('http') !== 0) continue;

                            // 4) استثناء صريح لروابط التنزيل
                            if (a4upIsDownloadUrl(watchUrl)) continue;

                            // اسم السيرفر
                            var nameEl = li.querySelector('.watch-server-name');
                            var name = nameEl ? a4upClean(nameEl.textContent || nameEl.innerText) : '';
                            if (!name) {
                                var alt = li.querySelector('.ser, .server-name');
                                if (alt) name = a4upClean(alt.textContent || alt.innerText);
                            }
                            if (!name) name = 'سيرفر ' + (i + 1);

                            // الجودة
                            var qualityEl = li.querySelector('.quality');
                            var quality = qualityEl ? a4upClean(qualityEl.textContent || qualityEl.innerText) : '';

                            // سيرفر مميز
                            var featured = li.classList.contains('watch-server-featured');

                            var displayName = name;
                            if (quality) displayName = name + ' — ' + quality;
                            if (featured) displayName = '★ ' + displayName;

                            // منع التكرار
                            var dup = false;
                            for (var r = 0; r < items.length; r++) {
                                if (items[r].url === watchUrl) { dup = true; break; }
                            }
                            if (dup) continue;

                            items.push({ name: displayName, url: watchUrl });
                        }

                        if (items.length > 0) {
                            a4upSend(items);
                            return;
                        }

                        // إعادة المحاولة إذا لم نجد شيئًا بعد
                        if (attempts < maxAttempts) {
                            setTimeout(tryExtract, 500);
                        } else {
                            // ⚠️ لا fallback — نرسل فشل نظيف بدل التقاط شيء عشوائي
                            a4upSend([]);
                        }
                    }

                    tryExtract();
                }

                // ========== تحديد نوع الصفحة ==========
                var host   = window.location.hostname;
                var path   = window.location.pathname;
                var search = window.location.search || '';

                if (host.indexOf('anime4up') === -1) {
                    a4upSend([]);
                    return;
                }

                setTimeout(function() {
                    try {
                        // 1) صفحة الحلقة
                        if (document.querySelector('#episode-servers')) {
                            a4upHandleEpisodePage();
                            return;
                        }

                        // 2) مسار /episode/
                        if (path.indexOf('/episode/') !== -1) {
                            a4upHandleEpisodePage();
                            return;
                        }

                        // 3) صفحة البحث
                        if (/[?&]s=/.test(search)) {
                            a4upHandleSearch();
                            return;
                        }

                        // 4) صفحة الأنمي
                        if (document.querySelector('#episodesList') ||
                            document.querySelector('.anime-info-container')) {
                            a4upHandleAnimePage();
                            return;
                        }

                        // 5) مسار /anime/
                        if (path.indexOf('/anime/') !== -1) {
                            a4upHandleAnimePage();
                            return;
                        }

                        a4upSend([]);
                    } catch (err) {
                        a4upSend([]);
                    }
                }, 700);
            })();
        """.trimIndent()
    }

    // ============================================================
    // دوال مساعدة
    // ============================================================

    private fun escapeForJs(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
