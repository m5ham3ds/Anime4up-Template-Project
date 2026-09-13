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
    override val iconUrl: String = "https://w1.anime4up.rest/wp-content/uploads/2019/03/Anime4up-Icon-1.png"

    override fun getSearchUrl(titleOriginal: String, titleClean: String): String {
        return "$baseUrl/?s=" + URLEncoder.encode(titleClean, "UTF-8")
    }

    override fun getExtractionScript(
        isMovie: Boolean,
        episode: Int,
        title: String
    ): String {
        val safeTitle = escapeForJs(title)
        val safeEpisode = if (episode > 0) episode else 0

        return """
            (function() {
                'use strict';

                // ============================================================
                //  أدوات الجسر — تعمل مع Host جاهز
                // ============================================================

                var BRIDGE = (typeof AndroidBridge !== 'undefined') ? AndroidBridge : null;

                function log(msg) {
                    try {
                        if (BRIDGE && typeof BRIDGE.logDebug === 'function') {
                            BRIDGE.logDebug('[A4UP] ' + msg);
                        } else {
                            console.log('[A4UP] ' + msg);
                        }
                    } catch(e) {}
                }

                function sendServers(items) {
                    if (!BRIDGE || typeof BRIDGE.sendServersV2 !== 'function') {
                        log('❌ لا يوجد جسر sendServersV2');
                        return;
                    }
                    try {
                        if (items && items.length > 0) {
                            log('✅ إرسال ' + items.length + ' سيرفر');
                            BRIDGE.sendServersV2(JSON.stringify(items), window.location.href);
                        } else {
                            log('⚠️ إرسال sendFailed');
                            BRIDGE.sendFailed();
                        }
                    } catch(e) {
                        log('❌ خطأ في الإرسال: ' + e.message);
                    }
                }

                function reportCloudflare() {
                    if (BRIDGE && typeof BRIDGE.sendBypassStatus === 'function') {
                        log('🛡️ كشف Cloudflare');
                        BRIDGE.sendBypassStatus('CLOUDFLARE');
                    }
                }

                function clean(txt) {
                    if (!txt) return '';
                    return String(txt).replace(/\s+/g, ' ').trim();
                }

                function extractNum(text) {
                    if (!text) return 0;
                    var arabic = '٠١٢٣٤٥٦٧٨٩';
                    var normalized = String(text).replace(/[٠-٩]/g, function(d) {
                        return arabic.indexOf(d);
                    });
                    var m = normalized.match(/(\d+)/);
                    return m ? parseInt(m[1], 10) : 0;
                }

                function isDownloadUrl(url) {
                    if (!url) return true;
                    var u = String(url).toLowerCase();
                    var patterns = [
                        'megamax.me/d/', 'megamax.me/download',
                        'mega.nz/#!', 'mega.nz/#F!',
                        'gofile.io/d/', 'file-upload.org',
                        'mediafire.com/file', 'workupload.com/file',
                        'dsvplay.com/d/', 'streamruby.com/d/',
                        'mp4upload.com/d/', 'uqload.is/d/', 'uqload.vc/d/'
                    ];
                    for (var i = 0; i < patterns.length; i++) {
                        if (u.indexOf(patterns[i]) !== -1) return true;
                    }
                    if (u.indexOf('/download/') !== -1) return true;
                    if (u.indexOf('/download?') !== -1) return true;
                    return false;
                }

                // ============================================================
                //  كشف Cloudflare قبل أي شيء
                // ============================================================

                function checkCloudflare() {
                    var cfIndicators = [
                        'cf-challenge', 'challenge-platform',
                        'cf-browser-verification', 'cf-please-wait'
                    ];
                    var html = document.documentElement.innerHTML.toLowerCase();
                    for (var i = 0; i < cfIndicators.length; i++) {
                        if (html.indexOf(cfIndicators[i]) !== -1) {
                            reportCloudflare();
                            return true;
                        }
                    }
                    if (document.title.indexOf('Just a moment') !== -1 ||
                        document.title.indexOf('Attention Required') !== -1) {
                        reportCloudflare();
                        return true;
                    }
                    return false;
                }

                // ============================================================
                //  مرحلة 1: البحث عن الأنمي
                // ============================================================

                function findBestMatch(searchTitle) {
                    var cards = document.querySelectorAll('.anime-card-themex');
                    if (!cards || cards.length === 0) {
                        cards = document.querySelectorAll('.anime-card-container');
                    }
                    if (!cards || cards.length === 0) return null;

                    var words = String(searchTitle).toLowerCase()
                        .split(/[\s:\.\-–—,،\(\)\[\]\/]+/)
                        .filter(function(w) { return w.length > 1; });

                    var bestLink = null;
                    var bestScore = -1;

                    for (var i = 0; i < cards.length; i++) {
                        var card = cards[i];
                        var isEpisodeCard = card.querySelector('.ep_num') !== null;

                        var linkEl = card.querySelector('.anime-card-poster a.overlay')
                                  || card.querySelector('.anime-card-title a')
                                  || card.querySelector('a[href*="/anime/"]');
                        var titleEl = card.querySelector('.anime-card-title h3 a')
                                   || card.querySelector('.anime-card-title h3')
                                   || card.querySelector('h3 a');
                        if (!linkEl) continue;

                        var href = linkEl.getAttribute('href') || '';
                        var isAnimeLink = href.indexOf('/anime/') !== -1;

                        var t = (titleEl ? titleEl.innerText : '').toLowerCase();
                        var sc = 0;
                        for (var w = 0; w < words.length; w++) {
                            if (t.indexOf(words[w]) !== -1) sc++;
                        }
                        if (words.length > 0) sc = sc / words.length;
                        if (isAnimeLink) sc += 0.15;
                        if (isEpisodeCard) sc -= 0.3;

                        if (sc > bestScore) {
                            bestScore = sc;
                            bestLink = linkEl;
                        }
                    }

                    if (bestScore >= 0.6) return bestLink;
                    return null;
                }

                function handleSearch() {
                    if (!A4UP_TITLE) { sendServers([]); return; }
                    var attempts = 0;
                    var max = 24;
                    var iv = setInterval(function() {
                        attempts++;
                        var link = findBestMatch(A4UP_TITLE);
                        if (link && link.href) {
                            clearInterval(iv);
                            log('الانتقال إلى: ' + link.href);
                            window.location.href = link.href;
                            return;
                        }
                        if (attempts >= max) {
                            clearInterval(iv);
                            log('فشل البحث');
                            sendServers([]);
                        }
                    }, 500);
                }

                // ============================================================
                //  مرحلة 2: اختيار الحلقة من صفحة الأنمي
                // ============================================================

                function handleAnimePage() {
                    if (document.querySelector('#episode-servers')) {
                        handleEpisodePage();
                        return;
                    }

                    var targetEp = A4UP_EPISODE > 0 ? A4UP_EPISODE : 1;

                    var anchors = document.querySelectorAll(
                        '#episodesList .anime-card-themex .ep_num a, ' +
                        '#episodesList .anime-card-themex a.overlay'
                    );
                    if (!anchors || anchors.length === 0) {
                        anchors = document.querySelectorAll('#ULEpisodesList li a, .all-episodes-list li a');
                    }
                    if (!anchors || anchors.length === 0) {
                        log('لا توجد حلقات على الصفحة');
                        sendServers([]);
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

                    if (A4UP_IS_MOVIE && targetEp <= 1) {
                        try { anchors[0].click(); }
                        catch(e) { window.location.href = anchors[0].href; }
                        return;
                    }

                    var found = null;
                    for (var j = 0; j < anchors.length; j++) {
                        var txt = clean(anchors[j].textContent || '');
                        var num = extractNum(txt);
                        if (num === targetEp) { found = anchors[j]; break; }
                    }

                    if (!found) {
                        log('الحلقة ' + targetEp + ' غير موجودة');
                        sendServers([]);
                        return;
                    }

                    log('النقر على الحلقة ' + targetEp);
                    try { found.click(); }
                    catch(e) { window.location.href = found.href; }
                }

                // ============================================================
                //  مرحلة 3: استخراج السيرفرات من صفحة الحلقة
                //  ملاحظة: querySelectorAll يعمل مع العناصر المخفية
                // ============================================================

                function handleEpisodePage() {
                    var attempt = 0;
                    var MAX_TRIES = 60;
                    var INTERVAL = 500;

                    function tryExtract() {
                        attempt++;
                        var container = document.getElementById('episode-servers');

                        if (!container) {
                            if (attempt < MAX_TRIES) {
                                setTimeout(tryExtract, INTERVAL);
                            } else {
                                log('لم تظهر قائمة السيرفرات');
                                sendServers([]);
                            }
                            return;
                        }

                        var items = [];
                        var lis = container.querySelectorAll('li');

                        for (var i = 0; i < lis.length; i++) {
                            var li = lis[i];
                            if (!li.hasAttribute('data-watch')) continue;

                            var watchUrl = clean(li.getAttribute('data-watch'));
                            if (!watchUrl || watchUrl.indexOf('http') !== 0) continue;
                            if (isDownloadUrl(watchUrl)) continue;

                            var nameEl = li.querySelector('.watch-server-name');
                            var name = nameEl ? clean(nameEl.textContent) : '';
                            if (!name) name = 'سيرفر ' + (i + 1);

                            // إزالة التكرار
                            var dup = false;
                            for (var r = 0; r < items.length; r++) {
                                if (items[r].url === watchUrl) { dup = true; break; }
                            }
                            if (dup) continue;

                            items.push({ name: name, url: watchUrl });
                        }

                        if (items.length > 0) {
                            log('تم استخراج ' + items.length + ' سيرفر');
                            sendServers(items);
                            return;
                        }

                        if (attempt < MAX_TRIES) {
                            setTimeout(tryExtract, INTERVAL);
                        } else {
                            log('انتهت المهلة');
                            sendServers([]);
                        }
                    }

                    tryExtract();
                }

                // ============================================================
                //  البوت الرئيسي
                // ============================================================

                function boot() {
                    // 1. افحص Cloudflare أولاً
                    if (checkCloudflare()) return;

                    // 2. إذا غادرنا نطاق anime4up — لا تفعل شيئاً
                    if (window.location.hostname.indexOf('anime4up') === -1) {
                        return;
                    }

                    log('URL: ' + window.location.href);

                    try {
                        var path = window.location.pathname;
                        var search = window.location.search || '';

                        // صفحة الحلقة
                        if (document.querySelector('#episode-servers') ||
                            path.indexOf('/episode/') !== -1) {
                            handleEpisodePage();
                            return;
                        }
                        // صفحة بحث
                        if (/[?&]s=/.test(search)) {
                            handleSearch();
                            return;
                        }
                        // صفحة أنمي
                        if (document.querySelector('#episodesList') ||
                            document.querySelector('.anime-info-container') ||
                            path.indexOf('/anime/') !== -1) {
                            handleAnimePage();
                            return;
                        }
                        sendServers([]);
                    } catch (err) {
                        log('خطأ: ' + err);
                        sendServers([]);
                    }
                }

                // متغيرات الإضافة
                var A4UP_TITLE = "$safeTitle";
                var A4UP_EPISODE = $safeEpisode;
                var A4UP_IS_MOVIE = $isMovie;

                // ابدأ بعد 800ms
                setTimeout(boot, 800);

            })();
        """.trimIndent()
    }

    private fun escapeForJs(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
