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
        // WordPress: /?s=QUERY
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

                // ========== اختيار أفضل تطابق من نتائج البحث ==========
                function a4upFindBestMatch(searchTitle) {
                    // البطاقات في صفحة البحث الرئيسية للقالب
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
                        // تجنب بطاقات الحلقات في الرئيسية (التي تحتوي ep_num)
                        var isEpisodeCard = card.querySelector('.ep_num') !== null;

                        var linkEl = card.querySelector('.anime-card-poster a.overlay')
                                  || card.querySelector('.anime-card-title a')
                                  || card.querySelector('a.overlay')
                                  || card.querySelector('a[href*="/anime/"]');
                        var titleEl = card.querySelector('.anime-card-title h3 a')
                                   || card.querySelector('.anime-card-title h3')
                                   || card.querySelector('h3 a');
                        if (!linkEl) continue;

                        // أفضلية للبطاقات التي تشير إلى صفحة أنمي وليس حلقة
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
                    // إذا كانت الصفحة تحتوي على سيرفرات (نحن في صفحة الحلقة) → المرحلة 3
                    if (document.querySelector('#episode-servers')) {
                        a4upHandleEpisodePage();
                        return;
                    }

                    var targetEp = parseInt(window.a4upTargetEpisode) || 1;

                    // نجمع روابط الحلقات
                    // أولاً: من صفحة الأنمي (#episodesList)
                    var episodeAnchors = document.querySelectorAll(
                        '#episodesList .anime-card-themex .ep_num a, ' +
                        '#episodesList .anime-card-themex a.overlay'
                    );

                    // إن لم توجد، نجرّب القائمة الجانبية (في صفحة الحلقة)
                    if (!episodeAnchors || episodeAnchors.length === 0) {
                        episodeAnchors = document.querySelectorAll(
                            '#ULEpisodesList li a, .all-episodes-list li a'
                        );
                    }

                    if (!episodeAnchors || episodeAnchors.length === 0) {
                        a4upSend([]);
                        return;
                    }

                    // إزالة التكرار (نفس الحلقة قد تظهر مرتين: عبر ep_num وعبر overlay)
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

                    // إذا كان فيلم → نأخذ أول حلقة
                    if (window.a4upIsMovie) {
                        episodeAnchors[0].click();
                        return;
                    }

                    // البحث عن الحلقة المطلوبة
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

                // ========== المرحلة 3: صفحة الحلقة (استخراج مباشر) ==========
                function a4upHandleEpisodePage() {
                    var attempts = 0;

                    function tryExtract() {
                        attempts++;
                        var items = [];

                        // ✅ هنا الفرق الأساسي: الروابط موجودة مباشرة في data-watch
                        var serverLis = document.querySelectorAll('#episode-servers li');
                        if (serverLis && serverLis.length > 0) {
                            for (var i = 0; i < serverLis.length; i++) {
                                var li = serverLis[i];
                                var watchUrl = li.getAttribute('data-watch');
                                if (!watchUrl) continue;

                                // الاسم من <bdi class="watch-server-name">
                                var nameEl = li.querySelector('.watch-server-name');
                                var name = nameEl ? a4upClean(nameEl.textContent || nameEl.innerText) : '';

                                // احتياطي: بعض الصفحات تستخدم <span class="ser">
                                if (!name) {
                                    var altEl = li.querySelector('.ser, .server-name, a');
                                    if (altEl) name = a4upClean(altEl.textContent || altEl.innerText);
                                }
                                if (!name) name = 'سيرفر ' + (i + 1);

                                // الجودة (اختياري)
                                var qualityEl = li.querySelector('.quality');
                                var quality = qualityEl ? a4upClean(qualityEl.textContent || qualityEl.innerText) : '';

                                // مميز
                                var featured = li.classList.contains('watch-server-featured');

                                // نضمّن الجودة في الاسم لتسهيل التمييز
                                var displayName = name;
                                if (quality) displayName = name + ' — ' + quality;
                                if (featured) displayName = '★ ' + displayName;

                                // تجنّب التكرار بنفس الرابط
                                var dup = false;
                                for (var r = 0; r < items.length; r++) {
                                    if (items[r].url === watchUrl) { dup = true; break; }
                                }
                                if (dup) continue;

                                items.push({ name: displayName, url: watchUrl });
                            }
                        }

                        if (items.length > 0) {
                            a4upSend(items);
                            return;
                        }

                        // نجرب مرة أخرى بعد مهلة (لعل السيرفرات تُحمّل لاحقًا)
                        if (attempts < 16) {
                            setTimeout(tryExtract, 500);
                        } else {
                            // احتياطي: جلب رابط iframe الحالي كسيرفر واحد
                            var iframe = document.querySelector('.videoWrapper iframe')
                                      || document.querySelector('#episode-player iframe');
                            if (iframe && iframe.src && iframe.src.indexOf('http') === 0) {
                                a4upSend([{ name: 'السيرفر الحالي', url: iframe.src }]);
                            } else {
                                a4upSend([]);
                            }
                        }
                    }

                    tryExtract();
                }

                // ========== تحديد نوع الصفحة وتنفيذ المنطق ==========
                var host   = window.location.hostname;
                var path   = window.location.pathname;
                var search = window.location.search || '';

                // التأكد من أننا على الموقع الصحيح
                if (host.indexOf('anime4up') === -1) {
                    a4upSend([]);
                    return;
                }

                setTimeout(function() {
                    try {
                        // 1) صفحة الحلقة (فيها سيرفرات)
                        if (document.querySelector('#episode-servers')) {
                            a4upHandleEpisodePage();
                            return;
                        }

                        // 2) مسار /episode/ (حتى لو تأخرت السيرفرات)
                        if (path.indexOf('/episode/') !== -1) {
                            a4upHandleEpisodePage();
                            return;
                        }

                        // 3) صفحة البحث (?s=)
                        if (/[?&]s=/.test(search)) {
                            a4upHandleSearch();
                            return;
                        }

                        // 4) صفحة الأنمي (فيها قائمة حلقات)
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

    /**
     * تهيئة النص للاستخدام داخل كود JavaScript بين علامتي اقتباس مزدوجتين.
     */
    private fun escapeForJs(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}