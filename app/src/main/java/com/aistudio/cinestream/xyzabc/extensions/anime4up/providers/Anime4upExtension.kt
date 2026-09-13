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
        // ✅ إذا وصل 0 أو سالب → نعتبره حلقة 1 افتراضياً في السكربت
        val safeEpisode = if (episode > 0) episode else 0

        return """
            (function() {
                'use strict';

                var A4UP_TITLE         = "$safeTitle";
                var A4UP_EPISODE       = $safeEpisode;
                var A4UP_IS_MOVIE      = $isMovie;

                var A4UP_SENT          = false;
                var A4UP_MAX_ATTEMPTS  = 60;
                var A4UP_INTERVAL_MS   = 500;

                // ✅ علم يمنع تسلسل التنقل إذا فشل أي مستوى
                var A4UP_FAILED        = false;

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

                function a4upLog(msg) {
                    try { console.log('[A4UP] ' + msg); } catch(e) {}
                }

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

                function a4upIsDownloadUrl(url) {
                    if (!url) return true;
                    var u = String(url).toLowerCase();
                    var downloadPatterns = [
                        'megamax.me/d/', 'megamax.me/download', 'mega.nz/#!', 'mega.nz/#F!',
                        'gofile.io/d/', 'file-upload.org', 'mediafire.com/file',
                        'workupload.com/file', 'dsvplay.com/d/', 'streamruby.com/d/',
                        'mp4upload.com/d/', 'uqload.is/d/', 'uqload.vc/d/'
                    ];
                    for (var i = 0; i < downloadPatterns.length; i++) {
                        if (u.indexOf(downloadPatterns[i]) !== -1) return true;
                    }
                    if (u.indexOf('/download/') !== -1) return true;
                    if (u.indexOf('/download?') !== -1) return true;
                    return false;
                }

                // ============================================================
                //  تطابق البحث — الآن صارم
                // ============================================================
                function a4upFindBestMatch(searchTitle) {
                    var cards = document.querySelectorAll('.anime-card-themex');
                    if (!cards || cards.length === 0) {
                        cards = document.querySelectorAll('.anime-card-container');
                    }
                    if (!cards || cards.length === 0) {
                        a4upLog('لا توجد نتائج بحث على الصفحة');
                        return null;
                    }

                    var words = String(searchTitle).toLowerCase()
                        .split(/[\s:\.\-–—,،\(\)\[\]\/]+/)
                        .filter(function(w) { return w.length > 1; });

                    a4upLog('كلمات البحث: ' + words.join('|'));

                    var bestLink  = null;
                    var bestScore = -1;
                    var bestTitle = '';

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

                        var t = (titleEl ? titleEl.innerText : '').toLowerCase();
                        var sc = 0;
                        for (var w = 0; w < words.length; w++) {
                            if (t.indexOf(words[w]) !== -1) sc++;
                        }
                        // نسبة التطابق
                        if (words.length > 0) {
                            sc = sc / words.length;   // ← من 0 إلى 1
                        }
                        if (isAnimeLink)   sc += 0.15;
                        if (isEpisodeCard) sc -= 0.3;

                        if (sc > bestScore) {
                            bestScore = sc;
                            bestLink  = linkEl;
                            bestTitle = t;
                        }
                    }

                    a4upLog('أفضل تطابق: "' + bestTitle + '" بنسبة ' + bestScore.toFixed(2));

                    // ✅ شرط صرامة: يجب تطابق 60% على الأقل
                    if (bestScore >= 0.6) {
                        return bestLink;
                    }
                    return null;
                }

                // ============================================================
                //  صفحة نتائج البحث
                // ============================================================
                function a4upHandleSearch() {
                    if (!A4UP_TITLE) {
                        a4upLog('عنوان البحث فارغ');
                        a4upSend([]);
                        return;
                    }
                    var attempts = 0;
                    var max = 24;   // 12 ثانية
                    var iv = setInterval(function() {
                        attempts++;
                        var link = a4upFindBestMatch(A4UP_TITLE);
                        if (link && link.href) {
                            clearInterval(iv);
                            a4upLog('الانتقال إلى: ' + link.href);
                            window.location.href = link.href;
                            return;
                        }
                        if (attempts >= max) {
                            clearInterval(iv);
                            a4upLog('فشل البحث بعد ' + attempts + ' محاولة');
                            a4upSend([]);
                        }
                    }, 500);
                }

                // ============================================================
                //  صفحة الأنمي — اختيار الحلقة
                // ============================================================
                function a4upHandleAnimePage() {
                    if (document.querySelector('#episode-servers')) {
                        a4upHandleEpisodePage();
                        return;
                    }

                    // ✅ إذا لم يمرر التطبيق رقم حلقة، اعتبره حلقة 1
                    var targetEp = A4UP_EPISODE > 0 ? A4UP_EPISODE : 1;

                    var anchors = document.querySelectorAll(
                        '#episodesList .anime-card-themex .ep_num a, ' +
                        '#episodesList .anime-card-themex a.overlay'
                    );
                    if (!anchors || anchors.length === 0) {
                        anchors = document.querySelectorAll('#ULEpisodesList li a, .all-episodes-list li a');
                    }
                    if (!anchors || anchors.length === 0) {
                        a4upLog('لا توجد حلقات على الصفحة');
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

                    a4upLog('عدد الحلقات المكتشفة: ' + anchors.length + ' | الهدف: ' + targetEp);

                    // ✅ فيلم: اختر الأول (فقط لو الحلقة المطلوبة = 1 أو 0)
                    if (A4UP_IS_MOVIE && (targetEp <= 1)) {
                        try { anchors[0].click(); }
                        catch (e) { window.location.href = anchors[0].href; }
                        return;
                    }

                    // ابحث عن الحلقة المطابقة
                    var found = null;
                    for (var j = 0; j < anchors.length; j++) {
                        var txt = a4upClean(anchors[j].textContent || anchors[j].innerText || '');
                        var num = a4upExtractEpisodeNumber(txt);
                        a4upLog('الحلقة المتاحة: "' + txt + '" → رقم ' + num);
                        if (num === targetEp) {
                            found = anchors[j];
                            break;
                        }
                    }

                    // ✅ إذا لم نجد الحلقة المطلوبة، لا ننقر على أي شيء
                    if (!found) {
                        a4upLog('الحلقة ' + targetEp + ' غير موجودة على الصفحة');
                        a4upSend([]);
                        return;
                    }

                    a4upLog('النقر على الحلقة ' + targetEp);
                    try { found.click(); }
                    catch (e) { window.location.href = found.href; }
                }

                // ============================================================
                //  صفحة الحلقة — استخراج السيرفرات
                // ============================================================
                function a4upHandleEpisodePage() {
                    var attempt = 0;
                    function tryExtract() {
                        attempt++;
                        var container = document.getElementById('episode-servers');
                        if (!container) {
                            if (attempt < A4UP_MAX_ATTEMPTS) {
                                setTimeout(tryExtract, A4UP_INTERVAL_MS);
                            } else {
                                a4upLog('لم تظهر قائمة السيرفرات');
                                a4upSend([]);
                            }
                            return;
                        }

                        var items = [];

                        var lis = Array.prototype.filter.call(
                            container.children,
                            function (el) { return el.tagName === 'LI'; }
                        );
                        if (!lis || lis.length === 0) {
                            lis = container.querySelectorAll('li');
                        }

                        for (var i = 0; i < lis.length; i++) {
                            var li = lis[i];
                            if (!li.hasAttribute('data-watch')) continue;

                            var watchUrl = a4upClean(li.getAttribute('data-watch'));
                            if (!watchUrl || watchUrl.indexOf('http') !== 0) continue;
                            if (a4upIsDownloadUrl(watchUrl)) continue;

                            var nameEl = li.querySelector('.watch-server-name');
                            var name = nameEl ? a4upClean(nameEl.textContent || nameEl.innerText) : '';
                            if (!name) {
                                var alt = li.querySelector('.ser, .server-name');
                                if (alt) name = a4upClean(alt.textContent || alt.innerText);
                            }
                            if (!name) name = 'سيرفر ' + (i + 1);

                            var dup = false;
                            for (var r = 0; r < items.length; r++) {
                                if (items[r].url === watchUrl) { dup = true; break; }
                            }
                            if (dup) continue;

                            items.push({
                                name: name,
                                url: watchUrl
                            });
                        }

                        if (items.length > 0) {
                            a4upLog('تم استخراج ' + items.length + ' سيرفر');
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

                // ============================================================
                //  البوت الرئيسي
                // ============================================================
                function a4upBoot() {
                    var host   = window.location.hostname;
                    var path   = window.location.pathname;
                    var search = window.location.search || '';

                    if (host.indexOf('anime4up') === -1) {
                        return;
                    }

                    a4upLog('URL: ' + window.location.href);

                    try {
                        // صفحة الحلقة
                        if (document.querySelector('#episode-servers') || path.indexOf('/episode/') !== -1) {
                            a4upHandleEpisodePage();
                            return;
                        }
                        // صفحة بحث
                        if (/[?&]s=/.test(search)) {
                            a4upHandleSearch();
                            return;
                        }
                        // صفحة أنمي
                        if (document.querySelector('#episodesList') ||
                            document.querySelector('.anime-info-container') ||
                            path.indexOf('/anime/') !== -1) {
                            a4upHandleAnimePage();
                            return;
                        }
                        a4upSend([]);
                    } catch (err) {
                        a4upLog('خطأ: ' + err);
                        a4upSend([]);
                    }
                }

                setTimeout(a4upBoot, 800);
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
